package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.SubmissionFingerprint;
import pl.codelobby.runner.model.BenchmarkReason;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSubmitBenchmarkRunnerTests {

    private final SubmitRequest request = JsonMapper.builder().build().readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final AtomicLong clock = new AtomicLong();
    private final JavaSubmitBenchmarkRunner runner = new JavaSubmitBenchmarkRunner(clock::get);
    private final Session session = new Session();

    @Test
    void discardsWarmupAndComputesMedianCpuAndMaximumMeasuredMemory() {
        double[] cpu = {1900, 1800, 50, 10, 30, 20, 40};
        long[] memory = {127000000, 126000000, 32000000, 36000000, 34000000, 35000000, 33000000};
        session.round = index -> summary(Verdict.ACCEPTED, cpu[index], 2000.0, memory[index], 0);

        var result = runner.run(request, session, Duration.ofSeconds(30));

        assertThat(session.calls).isEqualTo(7);
        assertThat(result.state()).isEqualTo(BenchmarkState.SUCCEEDED);
        assertThat(result.reason()).isNull();
        assertThat(result.metrics().runtimeMs()).isEqualTo(30);
        assertThat(result.metrics().sandboxCpuTimeMs()).isEqualTo(30);
        assertThat(result.metrics().peakMemoryBytes()).isEqualTo(36000000);
        assertThat(result.metrics().roundRuntimeMs()).containsExactly(50.0, 10.0, 30.0, 20.0, 40.0);
        assertThat(result.metrics().roundSandboxCpuTimeMs()).isEqualTo(result.metrics().roundRuntimeMs());
        assertThat(result.metrics().roundPeakMemoryBytes()).containsExactly(32000000L, 36000000L, 34000000L, 35000000L, 33000000L);
        assertThat(session.closed).isFalse();
        assertThat(session.limits).containsOnly(request.limits());
        assertThat(session.evaluation.status()).isEqualTo(Verdict.ACCEPTED);
    }

    @ParameterizedTest
    @EnumSource(value = Verdict.class, mode = EnumSource.Mode.EXCLUDE, names = "ACCEPTED")
    void skipsWithoutExecutingRoundsWhenEvaluationIsNotAccepted(Verdict verdict) {
        session.evaluation = summary(verdict, null, null, null, 100);
        var result = runner.run(request, session, Duration.ZERO);
        assertThat(result.state()).isEqualTo(BenchmarkState.SKIPPED);
        assertThat(result.reason()).isEqualTo(BenchmarkReason.EVALUATION_NOT_ACCEPTED);
        assertThat(result.metrics()).isNull();
        assertThat(session.calls).isZero();
    }

    @Test
    void deadlineShrinksButExecutionBudgetsAreFreshForEveryRound() {
        session.round = index -> {
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            return summary(Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 0);
        };
        assertThat(runner.run(request, session, Duration.ofSeconds(10)).state()).isEqualTo(BenchmarkState.SUCCEEDED);
        assertThat(session.deadlines).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(9),
                Duration.ofSeconds(8), Duration.ofSeconds(7), Duration.ofSeconds(6), Duration.ofSeconds(5), Duration.ofSeconds(4));
        assertThat(session.limits).hasSize(7).containsOnly(request.limits());
    }

    @Test
    void refusesExpiredBudgetWithoutLaunchingAnyRound() {
        var result = runner.run(request, session, Duration.ZERO);
        assertThat(result.state()).isEqualTo(BenchmarkState.FAILED);
        assertThat(result.reason()).isEqualTo(BenchmarkReason.BUDGET_EXCEEDED);
        assertThat(session.calls).isZero();
    }

    @Test
    void expirationAfterARoundPreventsAnotherLaunchAndDiscardsPartialMetrics() {
        session.round = index -> {
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            return summary(Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 0);
        };
        var result = runner.run(request, session, Duration.ofSeconds(4));
        assertThat(result.reason()).isEqualTo(BenchmarkReason.BUDGET_EXCEEDED);
        assertThat(result.metrics()).isNull();
        assertThat(session.calls).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 5, 6})
    void rejectsWrongAnswersInWarmupOrMeasuredRoundsWithoutChangingEvaluation(int failedRound) {
        session.round = index -> summary(index == failedRound ? Verdict.WRONG_ANSWER : Verdict.ACCEPTED,
                10.0, 20.0, 32000000L, 0);
        var result = runner.run(request, session, Duration.ofSeconds(30));
        assertThat(result.state()).isEqualTo(BenchmarkState.FAILED);
        assertThat(result.reason()).isEqualTo(BenchmarkReason.MEASUREMENT_UNAVAILABLE);
        assertThat(result.metrics()).isNull();
        assertThat(session.calls).isEqualTo(failedRound + 1);
        assertThat(session.evaluation.status()).isEqualTo(Verdict.ACCEPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "missing", "nan", "negative", "recompiled", "wrong-cases"})
    void rejectsUnavailableOrUntrustedMeasurements(String defect) {
        session.round = index -> switch (defect) {
            case "null" -> null;
            case "missing" -> summary(Verdict.ACCEPTED, null, null, null, 0);
            case "nan" -> summary(Verdict.ACCEPTED, Double.NaN, 20.0, 32000000L, 0);
            case "negative" -> summary(Verdict.ACCEPTED, -1.0, 20.0, 32000000L, 0);
            case "recompiled" -> summary(Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 1);
            default -> new JavaSubmitEvaluationRunner.Summary(Verdict.ACCEPTED, 0, 0, List.of(),
                    new Judge0CompilationUsage(0, 0), 10.0, 20.0, 32000000L);
        };
        var result = runner.run(request, session, Duration.ofSeconds(30));
        assertThat(result.state()).isEqualTo(BenchmarkState.FAILED);
        assertThat(result.reason()).isEqualTo(BenchmarkReason.MEASUREMENT_UNAVAILABLE);
        assertThat(result.metrics()).isNull();
        assertThat(session.calls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cpu", "wall", "memory", "timeout"})
    void reportsVerifiedRoundBudgetBreaches(String resource) {
        session.round = index -> summary(resource.equals("timeout") ? Verdict.TIME_LIMIT_EXCEEDED : Verdict.ACCEPTED,
                resource.equals("cpu") ? 2001.0 : 10.0, resource.equals("wall") ? 5001.0 : 20.0,
                resource.equals("memory") ? 128000001L : 32000000L, 0);
        var result = runner.run(request, session, Duration.ofSeconds(30));
        assertThat(result.reason()).isEqualTo(BenchmarkReason.BUDGET_EXCEEDED);
        assertThat(result.metrics()).isNull();
    }

    @Test
    void propagatesUncertainTerminationInsteadOfReturningAReleasableBenchmarkOutcome() {
        var failure = new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT);
        session.round = index -> { throw failure; };
        assertThatThrownBy(() -> runner.run(request, session, Duration.ofSeconds(30))).isSameAs(failure);
        assertThat(session.closed).isFalse();
        assertThat(session.calls).isEqualTo(1);
    }

    @Test
    void rejectsArtifactsFromAnotherIdOrPayloadBeforeExecutingAnyRound() {
        session.id = "other-submission";
        assertThatThrownBy(() -> runner.run(request, session, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        session.id = request.submissionId();
        var changed = new SubmitRequest(request.submissionId(), request.taskVersionId(), request.language(),
                request.taskFiles(), List.of(new SubmissionFile("src/Solution.java", "changed-source")),
                request.testSuites(), request.limits());
        assertThatThrownBy(() -> runner.run(changed, session, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("changed-source");
        assertThat(session.calls).isZero();
    }

    @Test
    void capsOverlongSuppliedDeadlineAtTheContractCeiling() {
        assertThat(runner.run(request, session, Duration.ofDays(1)).state()).isEqualTo(BenchmarkState.SUCCEEDED);
        assertThat(session.deadlines.getFirst()).isEqualTo(Duration.ofSeconds(180));
    }

    private JavaSubmitEvaluationRunner.Summary summary(Verdict status, Double cpu, Double wall, Long memory, double build) {
        var cases = request.testSuites().stream().flatMap(suite -> suite.cases().stream().map(test ->
                new TestResult(test.testId(), suite.suiteId(), suite.visibility(),
                        status == Verdict.ACCEPTED ? TestVerdict.ACCEPTED
                                : status == Verdict.WRONG_ANSWER ? TestVerdict.WRONG_ANSWER : TestVerdict.NOT_RUN,
                        null, null, null, null, null))).toList();
        return new JavaSubmitEvaluationRunner.Summary(status, status == Verdict.ACCEPTED ? cases.size() : 0,
                cases.size(), cases, new Judge0CompilationUsage(build, build), cpu, wall, memory);
    }

    private final class Session implements CompiledJavaSubmission {
        private String id = request.submissionId();
        private final String fingerprint = SubmissionFingerprint.compute(request);
        private JavaSubmitEvaluationRunner.Summary evaluation = summary(Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 100);
        private IntFunction<JavaSubmitEvaluationRunner.Summary> round = index -> summary(Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 0);
        private final List<ExecutionLimits> limits = new ArrayList<>();
        private final List<Duration> deadlines = new ArrayList<>();
        private int calls;
        private boolean closed;

        @Override
        public String submissionId() { return id; }

        @Override
        public String payloadFingerprint() { return fingerprint; }

        @Override
        public JavaSubmitEvaluationRunner.Summary evaluation() { return evaluation; }

        @Override
        public JavaSubmitEvaluationRunner.Summary executeRound(ExecutionLimits budget, Duration deadline) {
            limits.add(budget);
            deadlines.add(deadline);
            return round.apply(calls++);
        }

        @Override
        public void close() { closed = true; }
    }
}
