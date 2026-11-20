package pl.codelobby.runner.model;

import java.util.List;
import java.util.Objects;

/** Complete Submit evaluation data. This model does not execute tests or benchmarks. */
public record EvaluationResult(
        Verdict status,
        int passedTests,
        int totalTests,
        List<TestResult> testResults,
        String compileOutput,
        String message,
        Double sandboxCpuTimeMs,
        Long sandboxPeakMemoryBytes,
        Benchmark benchmark,
        GradingIntegrity gradingIntegrity) {

    public EvaluationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(benchmark, "benchmark");
        Objects.requireNonNull(gradingIntegrity, "gradingIntegrity");
        testResults = List.copyOf(testResults);
        long accepted = testResults.stream().filter(result -> result.status() == TestVerdict.ACCEPTED).count();
        if (totalTests < 1 || totalTests > 50 || totalTests != testResults.size()
                || passedTests != accepted || (status == Verdict.ACCEPTED && passedTests != totalTests)) {
            throw new IllegalArgumentException("Evaluation summary must match the individual test results");
        }
        if (testResults.stream().anyMatch(result -> result.visibility() == null || result.status() == null)) {
            throw new IllegalArgumentException("Evaluation cases require visibility and verdict");
        }
        if (compileOutput != null && testResults.stream()
                .anyMatch(result -> result.visibility() == TestVisibility.HIDDEN)) {
            throw new IllegalArgumentException("Evaluations including hidden tests must redact compiler diagnostics");
        }
        boolean validBenchmark = status == Verdict.ACCEPTED
                ? benchmark.state() == BenchmarkState.SUCCEEDED || benchmark.state() == BenchmarkState.FAILED
                : benchmark.state() == BenchmarkState.SKIPPED;
        if (!validBenchmark) {
            throw new IllegalArgumentException("Submit benchmark state must match the evaluation verdict");
        }
        if ((sandboxCpuTimeMs != null && (!Double.isFinite(sandboxCpuTimeMs) || sandboxCpuTimeMs < 0))
                || (sandboxPeakMemoryBytes != null && sandboxPeakMemoryBytes < 0)) {
            throw new IllegalArgumentException("Evaluation measurements must be finite and nonnegative when available");
        }
    }

    @Override
    public String toString() {
        return "EvaluationResult[<redacted>]";
    }
}
