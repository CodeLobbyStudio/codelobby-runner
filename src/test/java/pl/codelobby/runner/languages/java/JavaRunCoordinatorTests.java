package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.RunQueueFullException;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.RunResult;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class JavaRunCoordinatorTests {

    private final JavaRunExecutor executor = mock(JavaRunExecutor.class);
    private final AtomicLong clock = new AtomicLong();
    private RunRequest request;
    private RunResult result;

    @BeforeEach
    void readExamples() throws Exception {
        var mapper = JsonMapper.builder().build();
        request = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
        result = mapper.readValue(Path.of("docs/api/examples/run-accepted.json").toFile(), RunResponse.class).result();
    }

    @Test
    void rejectsUnsupportedLanguagesBeforeAdmission() {
        var coordinator = new JavaRunCoordinator(executor, 10, clock::get);
        var unsupported = new RunRequest(request.requestId(), request.taskVersionId(), Language.PYTHON,
                request.taskFiles(), request.submissionFiles(), request.testSuites(), request.limits());

        assertThatThrownBy(() -> coordinator.execute(unsupported))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ValidationErrorCode.INVALID_REQUEST));
        verifyNoInteractions(executor);
    }

    @Test
    void boundsActiveCapacityAndReleasesItAfterSuccessfulExecution() throws Exception {
        var coordinator = new JavaRunCoordinator(executor, 0, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(executor.execute(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        });
        var running = new FutureTask<>(() -> coordinator.execute(request));
        var thread = new Thread(running);
        thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> coordinator.execute(request)).isInstanceOf(RunQueueFullException.class);
        } finally {
            release.countDown();
        }
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(coordinator.execute(request)).isEqualTo(result);
        thread.join(1000);
    }

    @Test
    void executesQueuedRequestsInOrderAndSubtractsWaitingTime() throws Exception {
        var coordinator = new JavaRunCoordinator(executor, 2, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<Duration> budgets = Collections.synchronizedList(new ArrayList<>());
        when(executor.execute(any(), any())).thenAnswer(call -> {
            order.add(call.getArgument(0, RunRequest.class).requestId());
            budgets.add(call.getArgument(1));
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        });
        var first = new FutureTask<>(() -> coordinator.execute(request));
        var firstThread = new Thread(first);
        var second = new FutureTask<>(() -> coordinator.execute(withId("second")));
        var secondThread = new Thread(second);
        var third = new FutureTask<>(() -> coordinator.execute(withId("third")));
        var thirdThread = new Thread(third);
        firstThread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            secondThread.start();
            awaitQueueWait(secondThread);
            thirdThread.start();
            awaitQueueWait(thirdThread);
            assertThatThrownBy(() -> coordinator.execute(request)).isInstanceOf(RunQueueFullException.class);
            clock.set(Duration.ofSeconds(5).toNanos());
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(order).containsExactly(request.requestId(), "second", "third");
        assertThat(budgets).containsExactly(Duration.ofSeconds(60), Duration.ofSeconds(55), Duration.ofSeconds(55));
        firstThread.join(1000);
        secondThread.join(1000);
        thirdThread.join(1000);
    }

    @Test
    void quarantinesThePipelineAfterUnconfirmedEngineFailure() {
        var coordinator = new JavaRunCoordinator(executor, 10, clock::get);
        var failure = new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT);
        when(executor.execute(any(), any())).thenThrow(failure).thenReturn(result);

        assertThatThrownBy(() -> coordinator.execute(request)).isSameAs(failure);
        assertThatThrownBy(() -> coordinator.execute(request)).isInstanceOf(ExecutionUnavailableException.class);
    }

    @Test
    void validationFailureAllowsTheNextRequestToExecute() {
        var coordinator = new JavaRunCoordinator(executor, 10, clock::get);
        var failure = new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Unsupported language");
        when(executor.execute(any(), any())).thenThrow(failure).thenReturn(result);

        assertThatThrownBy(() -> coordinator.execute(request)).isSameAs(failure);
        assertThat(coordinator.execute(request)).isEqualTo(result);
    }

    @Test
    void queuedRequestDeadlineExpiresBeforeTheExecutorStarts() throws Exception {
        var coordinator = new JavaRunCoordinator(executor, 1, clock::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(executor.execute(any(), any())).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        });
        var first = new FutureTask<>(() -> coordinator.execute(request));
        var firstThread = new Thread(first);
        var second = new FutureTask<>(() -> coordinator.execute(request));
        var secondThread = new Thread(second);
        firstThread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            secondThread.start();
            awaitQueueWait(secondThread);
            clock.set(Duration.ofSeconds(60).toNanos());
        } finally {
            release.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(RunDeadlineExceededException.class);
        assertThat(calls).hasValue(1);
        assertThat(coordinator.execute(request)).isEqualTo(result);
        firstThread.join(1000);
        secondThread.join(1000);
    }

    private RunRequest withId(String id) {
        return new RunRequest(id, request.taskVersionId(), request.language(), request.taskFiles(),
                request.submissionFiles(), request.testSuites(), request.limits());
    }

    private static void awaitQueueWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }
}
