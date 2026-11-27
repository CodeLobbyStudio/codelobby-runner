package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.codelobby.runner.core.AsyncSubmitCoordinator;
import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.core.InMemoryEvaluationJobStore;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SubmissionFingerprint;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSubmitEvaluationExecutorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest request = mapper.readValue(Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final AtomicLong clock = new AtomicLong();
    private final Session session = new Session();
    private Duration openedWith;
    private Runnable onOpen = () -> {};
    private final JavaSubmitEvaluationExecutor executor = new JavaSubmitEvaluationExecutor((input, left) -> {
        openedWith = left;
        onOpen.run();
        return session;
    }, new JavaSubmitBenchmarkRunner(clock::get), clock::get);

    @Test
    void assemblesAcceptedResultUsingEvaluationMetricsAndSeparateBenchmarkMetrics() {
        var result = executor.evaluate(request, Duration.ofSeconds(30));
        assertThat(result.status()).isEqualTo(Verdict.ACCEPTED);
        assertThat(result.passedTests()).isEqualTo(2);
        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.benchmark().state()).isEqualTo(BenchmarkState.SUCCEEDED);
        assertThat(result.benchmark().metrics().sandboxCpuTimeMs()).isEqualTo(10);
        assertThat(result.sandboxCpuTimeMs()).isEqualTo(123);
        assertThat(result.sandboxPeakMemoryBytes()).isEqualTo(48000000);
        assertThat(result.compileOutput()).isNull();
        assertThat(result.message()).isNull();
        assertThat(result.testResults().getLast().actualOutput()).isNull();
        assertThat(session.rounds).isEqualTo(7);
        assertThat(session.closed).isTrue();
        assertThat(openedWith).isEqualTo(Duration.ofSeconds(30));
    }

    @ParameterizedTest
    @EnumSource(value = Verdict.class, mode = EnumSource.Mode.EXCLUDE, names = "ACCEPTED")
    void solutionFailuresReturnNormalResultsAndSkipBenchmark(Verdict status) {
        session.status = status;
        var result = executor.evaluate(request, Duration.ofSeconds(30));
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.benchmark().state()).isEqualTo(BenchmarkState.SKIPPED);
        assertThat(result.benchmark().metrics()).isNull();
        assertThat(session.rounds).isZero();
        assertThat(session.closed).isTrue();
    }

    @Test
    void verifiedBenchmarkFailurePreservesAcceptedEvaluation() {
        session.roundStatus = Verdict.WRONG_ANSWER;
        var result = executor.evaluate(request, Duration.ofSeconds(30));
        assertThat(result.status()).isEqualTo(Verdict.ACCEPTED);
        assertThat(result.passedTests()).isEqualTo(2);
        assertThat(result.benchmark().state()).isEqualTo(BenchmarkState.FAILED);
        assertThat(result.benchmark().metrics()).isNull();
        assertThat(session.closed).isTrue();
    }

    @Test
    void uncertainEngineFailurePropagatesAndClosesTheSession() {
        var failure = new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT);
        session.roundFailure = failure;
        assertThatThrownBy(() -> executor.evaluate(request, Duration.ofSeconds(30))).isSameAs(failure);
        assertThat(session.closed).isTrue();
    }

    @Test
    void openingAndBenchmarkShareTheCallerBudget() {
        onOpen = () -> clock.addAndGet(Duration.ofSeconds(4).toNanos());
        executor.evaluate(request, Duration.ofSeconds(10));
        assertThat(session.roundBudgets.getFirst()).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void deadlineIncludesSessionClose() {
        session.onClose = () -> clock.set(Duration.ofSeconds(31).toNanos());
        assertThatThrownBy(() -> executor.evaluate(request, Duration.ofSeconds(30)))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThat(session.closed).isTrue();
    }

    @Test
    void integratesWithAsyncLifecycleAndDoesNotReexecuteIdenticalSubmissions() throws Exception {
        var store = new InMemoryEvaluationJobStore();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        onOpen = () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Controlled session not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        };
        try (var coordinator = new AsyncSubmitCoordinator(store, new ExecutionQueue(1, 1), executor)) {
            assertThat(coordinator.submit(request).created()).isTrue();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.find(request.submissionId()).orElseThrow().state()).isEqualTo(JobState.RUNNING);
            assertThat(coordinator.submit(request).created()).isFalse();
            release.countDown();
            var job = awaitTerminal(store);
            assertThat(job.state()).isEqualTo(JobState.FINISHED);
            assertThat(job.result().benchmark().state()).isEqualTo(BenchmarkState.SUCCEEDED);
            assertThat(coordinator.submit(request).created()).isFalse();
            assertThat(session.rounds).isEqualTo(7);
        } finally {
            release.countDown();
        }
    }

    @Test
    void asyncInfrastructureFailureHasNoPartialResultAndQuarantinesCapacity() throws Exception {
        var store = new InMemoryEvaluationJobStore();
        var queue = new ExecutionQueue(1, 1);
        session.roundFailure = new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT);
        try (var coordinator = new AsyncSubmitCoordinator(store, queue, executor)) {
            coordinator.submit(request);
            var job = awaitTerminal(store);
            assertThat(job.state()).isEqualTo(JobState.FAILED);
            assertThat(job.result()).isNull();
            assertThat(job.error()).isNotNull();
            assertThatThrownBy(() -> queue.reserve(Duration.ofSeconds(1)))
                    .isInstanceOf(pl.codelobby.runner.core.ExecutionUnavailableException.class);
        }
    }

    private EvaluationJob awaitTerminal(InMemoryEvaluationJobStore store) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var job = store.find(request.submissionId()).orElseThrow();
            if (job.state() == JobState.FINISHED || job.state() == JobState.FAILED) return job;
            Thread.sleep(5);
        }
        throw new AssertionError("Controlled async job did not finish");
    }

    private JavaSubmitEvaluationRunner.Summary summary(Verdict status, double cpu, long memory, double build) {
        var cases = request.testSuites().stream().flatMap(suite -> suite.cases().stream().map(test ->
                new TestResult(test.testId(), suite.suiteId(), suite.visibility(),
                        status == Verdict.ACCEPTED ? TestVerdict.ACCEPTED : TestVerdict.NOT_RUN,
                        null, null, null, null, null))).toList();
        return new JavaSubmitEvaluationRunner.Summary(status, status == Verdict.ACCEPTED ? cases.size() : 0,
                cases.size(), cases, new Judge0CompilationUsage(build, build), cpu, cpu, memory);
    }

    private final class Session implements CompiledJavaSubmission {
        Verdict status = Verdict.ACCEPTED;
        Verdict roundStatus = Verdict.ACCEPTED;
        RuntimeException roundFailure;
        Runnable onClose = () -> {};
        boolean closed;
        int rounds;
        List<Duration> roundBudgets = new ArrayList<>();
        @Override public String submissionId() { return request.submissionId(); }
        @Override public String payloadFingerprint() { return SubmissionFingerprint.compute(request); }
        @Override public JavaSubmitEvaluationRunner.Summary evaluation() { return summary(status, 123, 48000000, 1000); }
        @Override public JavaSubmitEvaluationRunner.Summary executeRound(ExecutionLimits limits, Duration left) {
            rounds++;
            roundBudgets.add(left);
            if (roundFailure != null) throw roundFailure;
            return summary(roundStatus, 10, 32000000, 0);
        }
        @Override public void close() { closed = true; onClose.run(); }
    }
}
