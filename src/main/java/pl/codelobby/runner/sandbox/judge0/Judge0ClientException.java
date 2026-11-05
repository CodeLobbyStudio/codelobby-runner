package pl.codelobby.runner.sandbox.judge0;

import java.util.Objects;

/** Safe infrastructure failure. Callers must not automatically resubmit or assume execution stopped. */
public final class Judge0ClientException extends RuntimeException {

    public enum Reason {
        UNAVAILABLE, TIMEOUT, HTTP_ERROR, INVALID_RESPONSE, INTERRUPTED, ENGINE_ERROR
    }

    private final Reason reason;
    private final Judge0SubmissionToken submissionToken;

    public Judge0ClientException(Reason reason) {
        this(reason, null);
    }

    public Judge0ClientException(Reason reason, Judge0SubmissionToken submissionToken) {
        // Do not attach transport/parser causes: they can contain URLs or response fragments.
        super(submissionToken == null
                ? "Judge0 communication failed. Submission acceptance is unconfirmed."
                : "Judge0 result unavailable. Execution termination is unconfirmed.");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.submissionToken = submissionToken;
    }

    public Reason reason() {
        return reason;
    }

    /** Null when submission creation failed before a token was received. */
    public Judge0SubmissionToken submissionToken() {
        return submissionToken;
    }
}
