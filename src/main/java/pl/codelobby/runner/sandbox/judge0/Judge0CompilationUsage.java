package pl.codelobby.runner.sandbox.judge0;

/** External compiler process measurements, excluded from evaluation measurements. */
public record Judge0CompilationUsage(double cpuTimeMs, double wallTimeMs) {

    public Judge0CompilationUsage {
        if (!Double.isFinite(cpuTimeMs) || !Double.isFinite(wallTimeMs) || cpuTimeMs < 0 || wallTimeMs < 0) {
            throw new IllegalArgumentException("Invalid compilation measurements");
        }
    }
}
