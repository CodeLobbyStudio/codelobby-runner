package pl.codelobby.runner.model;

import java.util.List;
import java.util.Objects;

/** Evaluation summary for Run: public cases only, without a benchmark execution. */
public record RunResult(
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

    public RunResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(benchmark, "benchmark");
        Objects.requireNonNull(gradingIntegrity, "gradingIntegrity");
        testResults = List.copyOf(testResults);
        if (testResults.stream().anyMatch(result -> result.visibility() != TestVisibility.PUBLIC)) {
            throw new IllegalArgumentException("Run results must contain public tests only");
        }
        long accepted = testResults.stream().filter(result -> result.status() == TestVerdict.ACCEPTED).count();
        if (totalTests < 1 || totalTests > 50 || totalTests != testResults.size()
                || passedTests != accepted || (status == Verdict.ACCEPTED && passedTests != totalTests)) {
            throw new IllegalArgumentException("Run summary must match the individual test results");
        }
        if (benchmark.state() != BenchmarkState.NOT_REQUESTED) {
            throw new IllegalArgumentException("Run does not execute benchmarks");
        }
        if ((sandboxCpuTimeMs != null && (!Double.isFinite(sandboxCpuTimeMs) || sandboxCpuTimeMs < 0))
                || (sandboxPeakMemoryBytes != null && sandboxPeakMemoryBytes < 0)) {
            throw new IllegalArgumentException("Run measurements must be finite and nonnegative when available");
        }
    }
}
