package pl.codelobby.runner.sandbox.judge0;

import java.util.Objects;

/** Internal engine data, possibly containing hidden diagnostics. Never expose directly through the API. */
public record Judge0Result(
        Judge0SubmissionToken token,
        Judge0Status status,
        String stdout,
        String stderr,
        String compileOutput,
        String message,
        Double cpuTimeMs,
        Double wallTimeMs,
        Long memoryBytes,
        Integer exitCode,
        Integer exitSignal) {

    public Judge0Result {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(status, "status");
        if ((cpuTimeMs != null && (!Double.isFinite(cpuTimeMs) || cpuTimeMs < 0))
                || (wallTimeMs != null && (!Double.isFinite(wallTimeMs) || wallTimeMs < 0))
                || (memoryBytes != null && memoryBytes < 0)
                || (exitSignal != null && exitSignal < 0)) {
            throw new IllegalArgumentException("Invalid Judge0 measurements");
        }
    }

    @Override
    public String toString() {
        return "Judge0Result[status=" + status + ", diagnostics=<redacted>]";
    }
}
