package pl.codelobby.runner.model;

/** Aggregate evaluation budgets; units match the internal API contract. */
public record ExecutionLimits(int cpuTimeMs, int wallTimeMs, long memoryLimitBytes) {
}
