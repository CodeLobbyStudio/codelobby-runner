package pl.codelobby.runner.core;

import java.util.Objects;

/** Safe validation failure without request contents or source paths. */
public final class RequestValidationException extends IllegalArgumentException {

    private final ValidationErrorCode code;

    public RequestValidationException(ValidationErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ValidationErrorCode code() {
        return code;
    }
}
