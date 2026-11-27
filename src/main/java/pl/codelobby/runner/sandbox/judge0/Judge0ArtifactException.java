package pl.codelobby.runner.sandbox.judge0;

/** Artifact retrieval failure after terminal execution, not evidence about active process termination. */
public final class Judge0ArtifactException extends RuntimeException {
    public enum Reason { MISSING, TOO_LARGE, UNAVAILABLE, INVALID_RESPONSE, INTERRUPTED }
    private final Reason reason;

    public Judge0ArtifactException(Reason reason) {
        super("Private compiled artifact unavailable");
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}
