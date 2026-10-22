package pl.codelobby.runner.model;

import java.util.List;

/** Reported external measurements; this model does not perform benchmarks. */
public record BenchmarkMetrics(
        double runtimeMs,
        double sandboxCpuTimeMs,
        long peakMemoryBytes,
        List<Double> roundRuntimeMs,
        List<Double> roundSandboxCpuTimeMs,
        List<Long> roundPeakMemoryBytes) {

    public BenchmarkMetrics {
        roundRuntimeMs = List.copyOf(roundRuntimeMs);
        roundSandboxCpuTimeMs = List.copyOf(roundSandboxCpuTimeMs);
        roundPeakMemoryBytes = List.copyOf(roundPeakMemoryBytes);
        if (roundRuntimeMs.size() != 5 || roundSandboxCpuTimeMs.size() != 5
                || roundPeakMemoryBytes.size() != 5) {
            throw new IllegalArgumentException("Benchmark metrics require five measured rounds");
        }
        if (!Double.isFinite(runtimeMs) || runtimeMs < 0
                || !Double.isFinite(sandboxCpuTimeMs) || sandboxCpuTimeMs < 0
                || peakMemoryBytes < 0
                || roundRuntimeMs.stream().anyMatch(value -> !Double.isFinite(value) || value < 0)
                || roundSandboxCpuTimeMs.stream().anyMatch(value -> !Double.isFinite(value) || value < 0)
                || roundPeakMemoryBytes.stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("Benchmark measurements must be finite and nonnegative");
        }
    }
}
