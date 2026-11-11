package pl.codelobby.runner.core;

/** The shared Run admission capacity is full. */
public final class RunQueueFullException extends RuntimeException {

    public RunQueueFullException() {
        super("Run queue is full");
    }
}
