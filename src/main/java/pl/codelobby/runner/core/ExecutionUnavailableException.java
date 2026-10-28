package pl.codelobby.runner.core;

/** No verified executor is available; this does not represent a participant verdict. */
public final class ExecutionUnavailableException extends RuntimeException {
    public ExecutionUnavailableException() {
        super("Execution unavailable");
    }
}
