package pl.codelobby.runner.model;

import java.util.List;

/** Backend-supplied Submit payload. Validation, storage and scheduling are separate stages. */
public record SubmitRequest(
        String submissionId,
        String taskVersionId,
        Language language,
        List<TaskFile> taskFiles,
        List<SubmissionFile> submissionFiles,
        List<TestSuite> testSuites,
        ExecutionLimits limits) {

    @Override
    public String toString() {
        return "SubmitRequest[<redacted>]";
    }
}
