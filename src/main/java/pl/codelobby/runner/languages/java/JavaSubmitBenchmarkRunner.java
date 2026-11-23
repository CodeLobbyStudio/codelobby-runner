package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.SubmissionFingerprint;
import pl.codelobby.runner.core.SubmitRequestValidator;
import pl.codelobby.runner.model.Benchmark;
import pl.codelobby.runner.model.BenchmarkMetrics;
import pl.codelobby.runner.model.BenchmarkReason;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.MeasurementScope;
import pl.codelobby.runner.model.MeasurementTrust;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;

/** Benchmark policy only. Requires an artifact-preserving execution session supplied by its owner. */
public final class JavaSubmitBenchmarkRunner {

    private final SubmitRequestValidator validator = new SubmitRequestValidator();
    private final LongSupplier nanoTime;

    public JavaSubmitBenchmarkRunner() {
        this(System::nanoTime);
    }

    JavaSubmitBenchmarkRunner(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Benchmark run(SubmitRequest request, CompiledJavaSubmission session, Duration timeLeft) {
        long started = nanoTime.getAsLong();
        validator.validate(request);
        Objects.requireNonNull(session, "session");
        if (request.language() != Language.JAVA) {
            throw new IllegalArgumentException("Java benchmarking requires the Java language");
        }
        if (!request.submissionId().equals(session.submissionId())
                || !SubmissionFingerprint.compute(request).equals(session.payloadFingerprint())) {
            throw new IllegalArgumentException("Compiled artifacts belong to a different submission payload");
        }
        var evaluation = session.evaluation();
        if (!matches(request, evaluation)) {
            throw new IllegalArgumentException("Compiled evaluation does not match the selected request");
        }
        if (evaluation.status() != Verdict.ACCEPTED) {
            return benchmark(BenchmarkState.SKIPPED, BenchmarkReason.EVALUATION_NOT_ACCEPTED, null);
        }
        if (!accepted(evaluation)) {
            throw new IllegalArgumentException("Accepted evaluation requires all cases accepted");
        }
        if (timeLeft == null || timeLeft.isNegative() || timeLeft.isZero()) {
            return failed(BenchmarkReason.BUDGET_EXCEEDED);
        }
        long budget = timeLeft.compareTo(Duration.ofSeconds(180)) > 0
                ? Duration.ofSeconds(180).toNanos() : timeLeft.toNanos();
        var cpuRounds = new ArrayList<Double>(5);
        var memoryRounds = new ArrayList<Long>(5);
        for (int round = 0; round < 7; round++) {
            long left = budget - (nanoTime.getAsLong() - started);
            if (left <= 0) {
                return failed(BenchmarkReason.BUDGET_EXCEEDED);
            }
            // Exceptions propagate so uncertain process termination cannot release admission capacity.
            var outcome = session.executeRound(request.limits(), Duration.ofNanos(left));
            if (nanoTime.getAsLong() - started >= budget) {
                return failed(BenchmarkReason.BUDGET_EXCEEDED);
            }
            if (!matches(request, outcome)) {
                return failed(BenchmarkReason.MEASUREMENT_UNAVAILABLE);
            }
            if (outcome.status() == Verdict.TIME_LIMIT_EXCEEDED || outcome.status() == Verdict.MEMORY_LIMIT_EXCEEDED) {
                return failed(BenchmarkReason.BUDGET_EXCEEDED);
            }
            if (!accepted(outcome) || !measurementsAvailable(outcome)
                    || outcome.compilation().cpuTimeMs() != 0 || outcome.compilation().wallTimeMs() != 0) {
                return failed(BenchmarkReason.MEASUREMENT_UNAVAILABLE);
            }
            if (outcome.sandboxCpuTimeMs() > request.limits().cpuTimeMs()
                    || outcome.sandboxWallTimeMs() > request.limits().wallTimeMs()
                    || outcome.sandboxPeakMemoryBytes() > request.limits().memoryLimitBytes()) {
                return failed(BenchmarkReason.BUDGET_EXCEEDED);
            }
            if (round >= 2) {
                cpuRounds.add(outcome.sandboxCpuTimeMs());
                memoryRounds.add(outcome.sandboxPeakMemoryBytes());
            }
        }
        var sorted = cpuRounds.stream().sorted().toList();
        double median = sorted.get(2);
        long peak = memoryRounds.stream().mapToLong(Long::longValue).max().orElseThrow();
        var metrics = new BenchmarkMetrics(median, median, peak, cpuRounds, cpuRounds, memoryRounds);
        return benchmark(BenchmarkState.SUCCEEDED, null, metrics);
    }

    private static boolean matches(SubmitRequest request, JavaSubmitEvaluationRunner.Summary summary) {
        if (summary == null) {
            return false;
        }
        int index = 0;
        int passed = 0;
        for (var suite : request.testSuites()) {
            for (var test : suite.cases()) {
                if (index >= summary.testResults().size()) {
                    return false;
                }
                var result = summary.testResults().get(index++);
                if (!test.testId().equals(result.testId()) || !suite.suiteId().equals(result.suiteId())
                        || suite.visibility() != result.visibility() || result.status() == null) {
                    return false;
                }
                if (result.status() == TestVerdict.ACCEPTED) {
                    passed++;
                }
            }
        }
        return index == summary.testResults().size() && index == summary.totalTests() && passed == summary.passedTests();
    }

    private static boolean accepted(JavaSubmitEvaluationRunner.Summary summary) {
        return summary.status() == Verdict.ACCEPTED && summary.passedTests() == summary.totalTests();
    }

    private static boolean measurementsAvailable(JavaSubmitEvaluationRunner.Summary summary) {
        return summary.sandboxCpuTimeMs() != null && Double.isFinite(summary.sandboxCpuTimeMs())
                && summary.sandboxCpuTimeMs() >= 0 && summary.sandboxWallTimeMs() != null
                && Double.isFinite(summary.sandboxWallTimeMs()) && summary.sandboxWallTimeMs() >= 0
                && summary.sandboxPeakMemoryBytes() != null && summary.sandboxPeakMemoryBytes() >= 0;
    }

    private static Benchmark failed(BenchmarkReason reason) {
        return benchmark(BenchmarkState.FAILED, reason, null);
    }

    private static Benchmark benchmark(BenchmarkState state, BenchmarkReason reason, BenchmarkMetrics metrics) {
        return new Benchmark(state, reason, 2, 5,
                MeasurementScope.ALL_TEST_SUITES, MeasurementTrust.SANDBOX_EXTERNAL, metrics);
    }
}
