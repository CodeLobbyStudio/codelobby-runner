package pl.codelobby.runner.core;

import java.util.Objects;

import pl.codelobby.runner.model.TestVerdict;
import tools.jackson.databind.JsonNode;

/** Parsed host-reported case data. Suite identity and visibility come from trusted metadata. */
public record ReportedTestCase(
        String testId,
        TestVerdict status,
        JsonNode input,
        JsonNode expectedOutput,
        JsonNode actualOutput) {

    public ReportedTestCase {
        Objects.requireNonNull(testId, "testId");
        Objects.requireNonNull(status, "status");
    }

    @Override
    public String toString() {
        return "ReportedTestCase[status=" + status + ", data=<redacted>]";
    }
}
