package pl.codelobby.runner.core;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;

/**
 * Execution boundary for the public-test Run flow; Submit jobs are outside this interface.
 * The application use case must validate the request before invoking the executor.
 * Implementations must not compile or execute participant code on the application host.
 */
public interface SubmissionExecutor {

    RunResult execute(RunRequest request);
}
