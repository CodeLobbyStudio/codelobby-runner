package pl.codelobby.runner.core;

import pl.codelobby.runner.model.TestVerdict;

/** Safe report failure without source, test IDs or diagnostic values. */
public final class SuiteReportException extends IllegalArgumentException {

    private final TestVerdict verdict;

    SuiteReportException(TestVerdict verdict) {
        super("Invalid suite report");
        this.verdict = verdict;
    }

    public TestVerdict verdict() {
        return verdict;
    }
}
