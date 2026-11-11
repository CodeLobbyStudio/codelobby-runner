package pl.codelobby.runner.sandbox.judge0;

import java.nio.charset.StandardCharsets;

import pl.codelobby.runner.model.ExecutionLimits;

/** One controlled source file for the internal client, not the public multi-file Run request. */
public record Judge0Submission(String sourceCode, String stdin, ExecutionLimits limits) {

    public Judge0Submission {
        requireText(sourceCode, 131072);
        requireText(stdin, 65536);
        if (sourceCode.isBlank()) {
            throw new IllegalArgumentException("Invalid Judge0 submission");
        }
        validateLimits(limits);
    }

    static void validateLimits(ExecutionLimits limits) {
        if (limits == null
                || limits.cpuTimeMs() < 1 || limits.cpuTimeMs() > 2000
                || limits.wallTimeMs() < limits.cpuTimeMs() || limits.wallTimeMs() > 5000
                || limits.memoryLimitBytes() < 16000000L || limits.memoryLimitBytes() > 128000000L) {
            throw new IllegalArgumentException("Invalid Judge0 submission");
        }
    }

    private static void requireText(String value, int maxBytes) {
        if (value == null || value.length() > maxBytes
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("Invalid Judge0 submission text");
        }
    }

    @Override
    public String toString() {
        return "Judge0Submission[sourceCode=<redacted>, stdin=<redacted>, limits=" + limits + "]";
    }
}
