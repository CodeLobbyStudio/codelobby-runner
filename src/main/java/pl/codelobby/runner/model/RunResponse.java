package pl.codelobby.runner.model;

/** Complete response payload; it does not implement an HTTP endpoint. */
public record RunResponse(
        String requestId,
        String taskVersionId,
        String contractVersion,
        RunResult result) {
}
