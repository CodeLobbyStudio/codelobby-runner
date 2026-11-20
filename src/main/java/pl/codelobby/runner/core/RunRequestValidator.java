package pl.codelobby.runner.core;

import pl.codelobby.runner.model.RunRequest;

/** Run validation excludes hidden files and suites. */
public final class RunRequestValidator {

    private final EvaluationRequestValidator validator = new EvaluationRequestValidator();

    public void validate(RunRequest request) {
        validator.validate(EvaluationRequestValidator.Input.from(request));
    }

    public void validateTestSuites(RunRequest request) {
        validator.validateTestSuites(EvaluationRequestValidator.Input.from(request));
    }

    public void validateFiles(RunRequest request) {
        validator.validateFiles(EvaluationRequestValidator.Input.from(request));
    }
}
