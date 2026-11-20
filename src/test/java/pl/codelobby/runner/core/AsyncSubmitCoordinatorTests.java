package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.Verdict;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncSubmitCoordinatorTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest original = mapper.readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final EvaluationJobStore store = new InMemoryEvaluationJobStore();
    private final AtomicLong time = new AtomicLong();
    private final ExecutionQueue queue = new ExecutionQueue(1, 2, time::get);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100), action -> {
                var thread = new Thread(action, "test-submit");
                thread.setDaemon(true);
                return thread;
            });
    private final ManualDeadlines deadlines = new ManualDeadlines();
    private final List<CountDownLatch> releaseGates = new ArrayList<>();
    private AsyncSubmitCoordinator coordinator;

    @AfterEach
    void cleanup() throws Exception {
        releaseGates.forEach(CountDownLatch::countDown);
        if (coordinator != null) {
            coordinator.close();
        }
        workers.shutdownNow();
        deadlines.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(deadlines.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void returnsBeforeEvaluationCompletesAndReplaysWithoutExecutingAgain() throws Exception {
        var entered = new CountDownLatch(1);
        var release = releaseGate();
        var calls = new AtomicInteger();
        coordinator = create((input, budget) -> {
            calls.incrementAndGet();
            assertThat(budget).isEqualTo(Duration.ofSeconds(180));
            entered.countDown();
            await(release);
            return result("job-wrong-answer.json");
        });
        var receipt = coordinator.submit(request("job"));
        await(entered);
        assertThat(receipt.created()).isTrue();
        assertThat(store.find("job").orElseThrow().state()).isEqualTo(JobState.RUNNING);
        var replay = coordinator.submit(request("job"));
        assertThat(replay.created()).isFalse();
        assertThat(calls).hasValue(1);
        release.countDown();
        var finished = awaitState("job", JobState.FINISHED);
        assertThat(finished.result().status()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(finished.error()).isNull();
        assertThat(coordinator.submit(request("job")).created()).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void sharesRunCapacityAndStartsPendingSubmitAutomatically() throws Exception {
        var runEntered = new CountDownLatch(1);
        var release = releaseGate();
        var run = holdRun(runEntered, release);
        var calls = new AtomicInteger();
        coordinator = create((input, budget) -> {
            calls.incrementAndGet();
            return result("job-finished.json");
        });
        await(runEntered);
        var submitted = coordinator.submit(request("job"));
        assertThat(submitted.job().state()).isEqualTo(JobState.QUEUED);
        assertThat(calls).hasValue(0);
        release.countDown();
        assertThat(run.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitState("job", JobState.FINISHED).result().status()).isEqualTo(Verdict.ACCEPTED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void processingFailureStoresAGenericErrorAndQuarantinesNewWork() throws Exception {
        coordinator = create((input, budget) -> { throw new IllegalStateException("private-source-marker"); });
        coordinator.submit(request("job"));
        var failed = awaitState("job", JobState.FAILED);
        assertThat(failed.result()).isNull();
        assertThat(failed.error().code()).isEqualTo("EXECUTION_INFRASTRUCTURE_ERROR");
        assertThat(mapper.writeValueAsString(failed)).doesNotContain("private-source-marker");
        assertThatThrownBy(() -> coordinator.submit(request("new")))
                .isInstanceOf(ExecutionUnavailableException.class);
        assertThat(store.find("new")).isEmpty();
        assertThat(coordinator.submit(request("job")).created()).isFalse();
    }

    @Test
    void nullEvaluationIsAProcessingFailureRatherThanAnAcceptedResult() throws Exception {
        coordinator = create((input, budget) -> null);
        coordinator.submit(request("job"));
        assertThat(awaitState("job", JobState.FAILED).error().code())
                .isEqualTo("EXECUTION_INFRASTRUCTURE_ERROR");
    }

    @Test
    void preexecutionValidationFailureDoesNotQuarantineSharedCapacity() throws Exception {
        coordinator = create((input, budget) -> {
            throw new RequestValidationException(ValidationErrorCode.INVALID_SOURCE_FILES, "private-marker");
        });
        coordinator.submit(request("job"));
        var failed = awaitState("job", JobState.FAILED);
        assertThat(failed.error().code()).isEqualTo("INVALID_SOURCE_FILES");
        assertThat(failed.error().retryable()).isFalse();
        var next = queue.execute(Duration.ofSeconds(60), budget -> "run");
        assertThat(next).isEqualTo("run");
    }

    @Test
    void deadlineWhileQueuedNeverCallsEvaluatorAndDoesNotQuarantineRunningRun() throws Exception {
        var runEntered = new CountDownLatch(1);
        var release = releaseGate();
        var run = holdRun(runEntered, release);
        var calls = new AtomicInteger();
        coordinator = create((input, budget) -> {
            calls.incrementAndGet();
            return result("job-finished.json");
        });
        await(runEntered);
        coordinator.submit(request("job"));
        time.set(Duration.ofSeconds(180).toNanos());
        deadlines.expireAll();
        var failed = awaitState("job", JobState.FAILED);
        assertThat(failed.startedAt()).isNull();
        assertThat(failed.error().code()).isEqualTo("REQUEST_DEADLINE_EXCEEDED");
        assertThat(calls).hasValue(0);
        release.countDown();
        assertThat(run.get(5, TimeUnit.SECONDS)).isTrue();
        var next = queue.execute(Duration.ofSeconds(60), budget -> "run");
        assertThat(next).isEqualTo("run");
    }

    @Test
    void runningDeadlineInitiatesStopAndIgnoresALateSuccessfulResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = releaseGate();
        coordinator = create((input, budget) -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return result("job-finished.json");
        });
        coordinator.submit(request("job"));
        await(entered);
        time.set(Duration.ofSeconds(180).toNanos());
        deadlines.expireAll();
        var failed = awaitState("job", JobState.FAILED);
        assertThat(failed.error().code()).isEqualTo("REQUEST_DEADLINE_EXCEEDED");
        assertThatThrownBy(() -> queue.reserve(Duration.ofSeconds(60)))
                .isInstanceOf(ExecutionUnavailableException.class);
        assertThat(workers.getActiveCount()).isEqualTo(1);
        release.countDown();
        awaitIdle();
        assertThat(store.find("job")).contains(failed);
    }

    @Test
    void expiredBudgetCannotBecomeFinishedEvenIfTheTimerHasNotFired() throws Exception {
        var entered = new CountDownLatch(1);
        var release = releaseGate();
        coordinator = create((input, budget) -> {
            entered.countDown();
            await(release);
            return result("job-finished.json");
        });
        coordinator.submit(request("job"));
        await(entered);
        time.set(Duration.ofSeconds(180).toNanos());
        release.countDown();
        assertThat(awaitState("job", JobState.FAILED).error().code()).isEqualTo("REQUEST_DEADLINE_EXCEEDED");
    }

    @Test
    void shutdownCancelsQueuedJobsAndQuarantinesUnconfirmedActiveWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = releaseGate();
        var calls = new AtomicInteger();
        coordinator = create((input, budget) -> {
            calls.incrementAndGet();
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return result("job-finished.json");
        });
        coordinator.submit(request("active"));
        await(entered);
        coordinator.submit(request("pending"));
        coordinator.submit(request("buffered"));
        coordinator.close();
        assertThat(awaitState("buffered", JobState.FAILED).startedAt()).isNull();
        assertThat(awaitState("active", JobState.FAILED).error().code()).isEqualTo("EXECUTION_UNAVAILABLE");
        assertThat(awaitState("pending", JobState.FAILED).startedAt()).isNull();
        assertThat(calls).hasValue(1);
        assertThat(coordinator.submit(request("active")).created()).isFalse();
        assertThatThrownBy(() -> coordinator.submit(request("new")))
                .isInstanceOf(ExecutionUnavailableException.class);
        assertThat(store.find("new")).isEmpty();
        assertThatThrownBy(() -> queue.reserve(Duration.ofSeconds(60)))
                .isInstanceOf(ExecutionUnavailableException.class);
        release.countDown();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.find("active").orElseThrow().state()).isEqualTo(JobState.FAILED);
    }

    @Test
    void shutdownOfOnlyWaitingSubmitDoesNotQuarantineAnIndependentRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = releaseGate();
        var run = holdRun(entered, release);
        coordinator = create((input, budget) -> result("job-finished.json"));
        await(entered);
        coordinator.submit(request("pending"));
        coordinator.close();
        assertThat(awaitState("pending", JobState.FAILED).startedAt()).isNull();
        release.countDown();
        assertThat(run.get(5, TimeUnit.SECONDS)).isTrue();
        var next = queue.execute(Duration.ofSeconds(60), budget -> "available");
        assertThat(next).isEqualTo("available");
    }

    @Test
    void dispatcherRejectionFailsAcceptedJobAndReleasesItsReservation() throws Exception {
        workers.shutdown();
        coordinator = create((input, budget) -> result("job-finished.json"));
        var receipt = coordinator.submit(request("job"));
        assertThat(receipt.created()).isTrue();
        var failed = awaitState("job", JobState.FAILED);
        assertThat(failed.startedAt()).isNull();
        assertThat(failed.error().code()).isEqualTo("EXECUTION_INFRASTRUCTURE_ERROR");
        var next = queue.execute(Duration.ofSeconds(60), budget -> "available");
        assertThat(next).isEqualTo("available");
        assertThat(deadlines.getQueue()).isEmpty();
    }

    @Test
    void copiesNestedRequestListsBeforeFingerprintingAndDispatch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = releaseGate();
        var run = holdRun(entered, release);
        var received = new AtomicReference<SubmitRequest>();
        coordinator = create((input, budget) -> {
            received.set(input);
            return result("job-finished.json");
        });
        await(entered);
        var files = new ArrayList<>(original.taskFiles());
        var paths = new ArrayList<>(original.testSuites().getFirst().testFilePaths());
        var cases = new ArrayList<>(original.testSuites().getFirst().cases());
        var suites = new ArrayList<>(original.testSuites());
        var first = suites.getFirst();
        suites.set(0, new TestSuite(first.suiteId(), first.visibility(), paths, first.execution(), cases));
        coordinator.submit(new SubmitRequest("job", original.taskVersionId(), original.language(), files,
                original.submissionFiles(), suites, original.limits()));
        files.clear();
        paths.clear();
        cases.clear();
        suites.clear();
        release.countDown();
        assertThat(run.get(5, TimeUnit.SECONDS)).isTrue();
        awaitState("job", JobState.FINISHED);
        assertThat(received.get().taskFiles()).hasSize(4);
        assertThat(received.get().testSuites().getFirst().testFilePaths()).containsExactly("tests/PublicTests.java");
        assertThat(received.get().testSuites().getFirst().cases()).hasSize(1);
        assertThatThrownBy(() -> received.get().taskFiles().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void twoWorkersExecuteConcurrentlyAndPendingWorkProgressesWithoutResubmission() throws Exception {
        var shared = new ExecutionQueue(2, 1);
        var entered = new CountDownLatch(2);
        var release = releaseGate();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        coordinator = new AsyncSubmitCoordinator(store, shared, (input, budget) -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                entered.countDown();
                await(release);
                return result("job-finished.json");
            } finally {
                active.decrementAndGet();
            }
        }, workers, deadlines);
        coordinator.submit(request("first"));
        coordinator.submit(request("second"));
        await(entered);
        assertThat(coordinator.submit(request("pending")).job().state()).isEqualTo(JobState.QUEUED);
        assertThatThrownBy(() -> coordinator.submit(request("overflow"))).isInstanceOf(RunQueueFullException.class);
        assertThat(store.find("overflow")).isEmpty();
        release.countDown();
        awaitState("first", JobState.FINISHED);
        awaitState("second", JobState.FINISHED);
        awaitState("pending", JobState.FINISHED);
        awaitIdle();
        assertThat(peak).hasValue(2);
        assertThat(active).hasValue(0);
        assertThat(deadlines.getQueue()).isEmpty();
    }

    private AsyncSubmitCoordinator create(EvaluationExecutor evaluator) {
        return new AsyncSubmitCoordinator(store, queue, evaluator, workers, deadlines);
    }

    private FutureTask<Boolean> holdRun(CountDownLatch entered, CountDownLatch release) {
        var run = new FutureTask<>(() -> queue.execute(Duration.ofSeconds(60), budget -> {
            entered.countDown();
            await(release);
            return true;
        }));
        var thread = new Thread(run, "test-held-run");
        thread.setDaemon(true);
        thread.start();
        return run;
    }

    private CountDownLatch releaseGate() {
        var gate = new CountDownLatch(1);
        releaseGates.add(gate);
        return gate;
    }

    private SubmitRequest request(String id) {
        return new SubmitRequest(id, original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), original.limits());
    }

    private EvaluationResult result(String file) {
        return mapper.readValue(Path.of("docs/api/examples", file).toFile(), EvaluationJob.class).result();
    }

    private EvaluationJob awaitState(String id, JobState expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        EvaluationJob last = null;
        while (System.nanoTime() < deadline) {
            last = store.find(id).orElseThrow();
            if (last.state() == expected) {
                return last;
            }
            Thread.sleep(5);
        }
        assertThat(last.state()).isEqualTo(expected);
        return last;
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (workers.getActiveCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(workers.getActiveCount()).isZero();
    }

    private static void await(CountDownLatch gate) {
        try {
            assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExecutionUnavailableException();
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch gate) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                    return;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ManualDeadlines extends ScheduledThreadPoolExecutor {
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

        ManualDeadlines() {
            super(1, action -> {
                var thread = new Thread(action, "test-deadline");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            callbacks.add(command);
            return super.schedule(command, 1, TimeUnit.DAYS);
        }

        void expireAll() {
            List.copyOf(callbacks).forEach(Runnable::run);
        }
    }
}
