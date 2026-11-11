package pl.codelobby.runner.core;

/** Operation deadline elapsed. The active engine submission may still require cleanup. */
public final class RunDeadlineExceededException extends RuntimeException {

    public RunDeadlineExceededException() {
        super("Run deadline exceeded");
    }
}
