package pl.codelobby.runner.api;

/** Safe, fixed-shape error response from the internal API contract. */
public record ApiError(String code, String message, boolean retryable) {
}
