package pl.codelobby.runner.core;

import pl.codelobby.runner.model.SubmitRequest;

/** Complete selected manifest validation before Submit storage or capacity admission. */
public final class SubmitRequestValidator {

    private final EvaluationRequestValidator validator = new EvaluationRequestValidator();

    public void validate(SubmitRequest request) {
        validator.validate(EvaluationRequestValidator.Input.from(request));
    }
}
