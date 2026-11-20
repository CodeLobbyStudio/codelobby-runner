package pl.codelobby.runner.model;

import java.util.Set;

/** Contract error data. Callers must provide generic messages, never raw engine diagnostics. */
public record JobError(String code, String message, boolean retryable) {

    private static final Set<String> CODES = Set.of(
            "INVALID_REQUEST", "INVALID_SOURCE_FILES", "UNAUTHORIZED", "JOB_NOT_FOUND",
            "SUBMISSION_ID_CONFLICT", "PAYLOAD_TOO_LARGE", "QUEUE_CAPACITY_EXCEEDED",
            "EXECUTION_UNAVAILABLE", "REQUEST_DEADLINE_EXCEEDED", "INTERNAL_SERVER_ERROR",
            "EXECUTION_INFRASTRUCTURE_ERROR");

    public JobError {
        if (code == null || !CODES.contains(code) || message == null || message.isBlank()
                || message.codePointCount(0, message.length()) > 1024) {
            throw new IllegalArgumentException("Invalid job error payload");
        }
    }

    @Override
    public String toString() {
        return "JobError[<redacted>]";
    }
}
