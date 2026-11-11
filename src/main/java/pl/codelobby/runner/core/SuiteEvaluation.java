package pl.codelobby.runner.core;

import java.util.List;
import java.util.Objects;

import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.Verdict;

/** Per-suite verdict and ordered case results for later request-level aggregation. */
public record SuiteEvaluation(Verdict status, List<TestResult> testResults) {

    private static final List<Verdict> PRIORITY = List.of(
            Verdict.COMPILATION_ERROR, Verdict.EXECUTION_ERROR, Verdict.OUTPUT_LIMIT_EXCEEDED,
            Verdict.MEMORY_LIMIT_EXCEEDED, Verdict.TIME_LIMIT_EXCEEDED, Verdict.RUNTIME_ERROR,
            Verdict.WRONG_ANSWER, Verdict.ACCEPTED);

    public static Verdict higherPriority(Verdict first, Verdict second) {
        return PRIORITY.indexOf(first) <= PRIORITY.indexOf(second) ? first : second;
    }

    public SuiteEvaluation {
        Objects.requireNonNull(status, "status");
        testResults = List.copyOf(testResults);
    }

    @Override
    public String toString() {
        return "SuiteEvaluation[status=" + status + ", testCount=" + testResults.size() + "]";
    }
}
