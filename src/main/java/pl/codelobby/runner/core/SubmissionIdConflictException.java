package pl.codelobby.runner.core;

/** The same retained submission ID was used with different evaluation data. */
public final class SubmissionIdConflictException extends RuntimeException {

    public SubmissionIdConflictException() {
        super("Submission ID conflicts with an existing job");
    }
}
