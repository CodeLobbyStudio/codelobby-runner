package pl.codelobby.runner.core;

/** No unexpired job is evicted to admit another submission. */
public final class JobStoreFullException extends RuntimeException {

    public JobStoreFullException() {
        super("Job storage capacity exceeded");
    }
}
