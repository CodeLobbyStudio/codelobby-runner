package pl.codelobby.runner.model;

import tools.jackson.databind.JsonNode;

/** One reported case; public diagnostics may contain any JSON value. */
public record TestResult(
        String testId,
        String suiteId,
        TestVisibility visibility,
        TestVerdict status,
        JsonNode input,
        JsonNode expectedOutput,
        JsonNode actualOutput,
        String stdout,
        String stderr) {

    public TestResult {
        input = nullIfJsonNull(input);
        expectedOutput = nullIfJsonNull(expectedOutput);
        actualOutput = nullIfJsonNull(actualOutput);

        if (visibility == TestVisibility.HIDDEN
                && (input != null || expectedOutput != null || actualOutput != null
                || stdout != null || stderr != null)) {
            throw new IllegalArgumentException("Hidden test results must not contain diagnostic data");
        }
    }

    private static JsonNode nullIfJsonNull(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }
}
