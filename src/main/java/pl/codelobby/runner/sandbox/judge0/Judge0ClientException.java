package pl.codelobby.runner.sandbox.judge0;

import java.util.Objects;

/** Safe infrastructure failure. Callers must not automatically resubmit or assume execution stopped. */
public final class Judge0ClientException extends RuntimeException {

    public enum Reason {
        UNAVAILABLE, TIMEOUT, HTTP_ERROR, INVALID_RESPONSE, INTERRUPTED
    }

    private final Reason reason;

    public Judge0ClientException(Reason reason) {
        // Do not attach transport/parser causes: they can contain URLs or response fragments.
        super("Judge0 communication failed. Submission acceptance is unconfirmed.");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
