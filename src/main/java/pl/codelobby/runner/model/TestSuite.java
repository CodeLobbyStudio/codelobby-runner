package pl.codelobby.runner.model;

import java.util.List;

/** Ordered Host test cases, files and entry metadata; does not execute any code. */
public record TestSuite(
        String suiteId,
        TestVisibility visibility,
        List<String> testFilePaths,
        ExecutionProfile execution,
        List<TestCaseDefinition> cases) {
}
