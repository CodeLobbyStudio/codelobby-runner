package pl.codelobby.runner.model;

import java.util.Objects;

/** Benchmark policy and outcome, independent of the solution verdict. */
public record Benchmark(
        BenchmarkState state,
        BenchmarkReason reason,
        int warmupRounds,
        int measuredRounds,
        MeasurementScope measurementScope,
        MeasurementTrust measurementTrust,
        BenchmarkMetrics metrics) {

    public Benchmark {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(measurementScope, "measurementScope");
        Objects.requireNonNull(measurementTrust, "measurementTrust");
        if (warmupRounds != 2 || measuredRounds != 5) {
            throw new IllegalArgumentException("The contract requires two warmup and five measured rounds");
        }
        boolean valid = switch (state) {
            case SUCCEEDED -> reason == null && metrics != null;
            case NOT_REQUESTED -> reason == BenchmarkReason.RUN_OPERATION && metrics == null;
            case SKIPPED -> reason == BenchmarkReason.EVALUATION_NOT_ACCEPTED && metrics == null;
            case FAILED -> metrics == null && (reason == BenchmarkReason.MEASUREMENT_UNAVAILABLE
                    || reason == BenchmarkReason.BUDGET_EXCEEDED
                    || reason == BenchmarkReason.INFRASTRUCTURE_FAILURE);
        };
        if (!valid) {
            throw new IllegalArgumentException("Benchmark state, reason and metrics are inconsistent");
        }
    }
}
