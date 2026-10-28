package pl.codelobby.runner.core;

import java.util.Objects;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;

/** Coordinates Run validation and execution without depending on HTTP or a sandbox implementation. */
public final class RunSubmissionUseCase {

    private static final String CONTRACT_VERSION = "1.0.0-draft.2";

    private final RunRequestValidator validator;
    private final SubmissionExecutor executor;

    public RunSubmissionUseCase(RunRequestValidator validator, SubmissionExecutor executor) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public RunResponse execute(RunRequest request) {
        validator.validate(request);
        var result = executor.execute(request);
        if (result == null) {
            throw new IllegalStateException("Executor did not return a result");
        }
        int index = 0;
        for (var suite : request.testSuites()) {
            for (var testCase : suite.cases()) {
                if (index >= result.testResults().size()) {
                    throw invalidResult();
                }
                var actual = result.testResults().get(index++);
                if (!suite.suiteId().equals(actual.suiteId()) || !testCase.testId().equals(actual.testId())) {
                    throw invalidResult();
                }
            }
        }
        if (index != result.testResults().size()) {
            throw invalidResult();
        }
        return new RunResponse(request.requestId(), request.taskVersionId(), CONTRACT_VERSION, result);
    }

    private IllegalStateException invalidResult() {
        return new IllegalStateException("Executor result does not match the declared tests");
    }
}
