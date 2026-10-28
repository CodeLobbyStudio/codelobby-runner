package pl.codelobby.runner.model;

import java.util.List;

/** Internal Run payload; semantic validation and HTTP handling are separate steps. */
public record RunRequest(
        String requestId,
        String taskVersionId,
        Language language,
        List<TaskFile> taskFiles,
        List<SubmissionFile> submissionFiles,
        List<TestSuite> testSuites,
        ExecutionLimits limits) {
}
