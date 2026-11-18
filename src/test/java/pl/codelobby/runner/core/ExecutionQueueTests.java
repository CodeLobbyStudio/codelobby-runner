package pl.codelobby.runner.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionQueueTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private final AtomicLong clock = new AtomicLong();

    @Test
    void boundsActiveCapacityAndReleasesItAfterSuccess() throws Exception {
        var queue = new ExecutionQueue(0, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var running = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            entered.countDown();
            await(release);
            return "run";
        }));
        var thread = new Thread(running);
        thread.start();
        try {
            await(entered);
            assertThatThrownBy(() -> queue.execute(TIMEOUT, remaining -> "other operation"))
                    .isInstanceOf(RunQueueFullException.class);
        } finally {
            release.countDown();
        }
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("run");
        var nextResult = queue.execute(TIMEOUT, remaining -> "other operation");
        assertThat(nextResult).isEqualTo("other operation");
        thread.join(1000);
    }

    @Test
    void sharesFifoOrderAndWaitingBudgetAcrossDifferentOperations() throws Exception {
        var queue = new ExecutionQueue(2, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<Duration> budgets = Collections.synchronizedList(new ArrayList<>());
        var first = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            order.add("run");
            budgets.add(remaining);
            entered.countDown();
            await(release);
            return "run";
        }));
        var second = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            order.add("another caller");
            budgets.add(remaining);
            return 2;
        }));
        var third = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            order.add("third caller");
            budgets.add(remaining);
            return true;
        }));
        var firstThread = new Thread(first);
        var secondThread = new Thread(second);
        var thirdThread = new Thread(third);
        firstThread.start();
        try {
            await(entered);
            secondThread.start();
            awaitQueueWait(secondThread);
            thirdThread.start();
            awaitQueueWait(thirdThread);
            assertThatThrownBy(() -> queue.execute(TIMEOUT, remaining -> "overflow"))
                    .isInstanceOf(RunQueueFullException.class);
            clock.set(Duration.ofSeconds(5).toNanos());
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("run");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(third.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("run", "another caller", "third caller");
        assertThat(budgets).containsExactly(TIMEOUT, Duration.ofSeconds(55), Duration.ofSeconds(55));
        firstThread.join(1000);
        secondThread.join(1000);
        thirdThread.join(1000);
    }

    @Test
    void expiresQueuedWorkBeforeInvokingItAndAllowsNewWork() throws Exception {
        var queue = new ExecutionQueue(1, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invoked = new AtomicBoolean();
        var first = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            entered.countDown();
            await(release);
            return "first";
        }));
        var second = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            invoked.set(true);
            return "expired";
        }));
        var firstThread = new Thread(first);
        var secondThread = new Thread(second);
        firstThread.start();
        try {
            await(entered);
            secondThread.start();
            awaitQueueWait(secondThread);
            clock.set(TIMEOUT.toNanos());
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RunDeadlineExceededException.class);
        assertThat(invoked).isFalse();
        var nextResult = queue.execute(TIMEOUT, remaining -> "new work");
        assertThat(nextResult).isEqualTo("new work");
        firstThread.join(1000);
        secondThread.join(1000);
    }

    @Test
    void releasesTimedOutWaitingCapacityWithoutQuarantiningActiveWork() throws Exception {
        var queue = new ExecutionQueue(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var first = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            entered.countDown();
            await(release);
            return "first";
        }));
        var thread = new Thread(first);
        thread.start();
        try {
            await(entered);
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> queue.execute(Duration.ofMillis(20), remaining -> calls.incrementAndGet()))
                        .isInstanceOf(RunDeadlineExceededException.class);
            }
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(calls).hasValue(0);
        var nextResult = queue.execute(TIMEOUT, remaining -> "next");
        assertThat(nextResult).isEqualTo("next");
        thread.join(1000);
    }

    @Test
    void quarantinesWaitingAndFutureCallersAfterUnconfirmedExecutionFailure() throws Exception {
        var queue = new ExecutionQueue(1, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invoked = new AtomicBoolean();
        var failure = new IllegalStateException("Engine termination unconfirmed");
        var first = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            entered.countDown();
            await(release);
            throw failure;
        }));
        var second = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            invoked.set(true);
            return "must not run";
        }));
        var firstThread = new Thread(first);
        var secondThread = new Thread(second);
        firstThread.start();
        try {
            await(entered);
            secondThread.start();
            awaitQueueWait(secondThread);
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCause(failure);
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ExecutionUnavailableException.class);
        assertThatThrownBy(() -> queue.execute(TIMEOUT, remaining -> "new caller"))
                .isInstanceOf(ExecutionUnavailableException.class);
        assertThat(invoked).isFalse();
        firstThread.join(1000);
        secondThread.join(1000);
    }

    @Test
    void releasesCapacityAfterValidationFailure() {
        var queue = new ExecutionQueue(0, clock::get);
        var failure = new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Invalid operation");
        assertThatThrownBy(() -> queue.execute(TIMEOUT, remaining -> { throw failure; })).isSameAs(failure);
        var nextResult = queue.execute(TIMEOUT, remaining -> "accepted");
        assertThat(nextResult).isEqualTo("accepted");
    }

    @Test
    void restoresInterruptFlagAndReleasesWaitingCapacity() throws Exception {
        var queue = new ExecutionQueue(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var first = new FutureTask<>(() -> queue.execute(TIMEOUT, remaining -> {
            entered.countDown();
            await(release);
            return "first";
        }));
        var second = new FutureTask<>(() -> {
            try {
                return queue.execute(TIMEOUT, remaining -> "must not run");
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        var firstThread = new Thread(first);
        var secondThread = new Thread(second);
        firstThread.start();
        try {
            await(entered);
            secondThread.start();
            awaitQueueWait(secondThread);
            secondThread.interrupt();
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionUnavailableException.class);
            assertThat(interrupted).isTrue();
            assertThatThrownBy(() -> queue.execute(Duration.ofMillis(20), remaining -> "waiting"))
                    .isInstanceOf(RunDeadlineExceededException.class);
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        var nextResult = queue.execute(TIMEOUT, remaining -> "next");
        assertThat(nextResult).isEqualTo("next");
        firstThread.join(1000);
        secondThread.join(1000);
    }

    @Test
    void rejectsInvalidArgumentsBeforeConsumingCapacity() {
        assertThatThrownBy(() -> new ExecutionQueue(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionQueue(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        var queue = new ExecutionQueue(0);
        assertThatThrownBy(() -> queue.execute(Duration.ZERO, remaining -> "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.execute(Duration.ofSeconds(-1), remaining -> "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.execute(TIMEOUT, null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> queue.execute(TIMEOUT, remaining -> "valid")).doesNotThrowAnyException();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitQueueWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }
}
