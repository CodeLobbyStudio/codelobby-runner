package pl.codelobby.runner.core;

import java.util.Objects;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;

/**
 * Test-only executor returning a configured result, without inspecting or executing source.
 * Tests must supply a result matching the request's declared cases.
 * This class is not a Spring bean and is not packaged in the application artifact.
 */
final class StubSubmissionExecutor implements SubmissionExecutor {

    private final RunResult result;

    StubSubmissionExecutor(RunResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public RunResult execute(RunRequest request) {
        Objects.requireNonNull(request, "request");
        return result;
    }
}
