package pl.codelobby.runner.core;

/** Contract error codes; HTTP status mapping belongs to the API layer. */
public enum ValidationErrorCode {
    INVALID_REQUEST,
    INVALID_SOURCE_FILES,
    PAYLOAD_TOO_LARGE
}
