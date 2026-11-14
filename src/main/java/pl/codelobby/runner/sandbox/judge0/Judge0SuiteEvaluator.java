package pl.codelobby.runner.sandbox.judge0;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.codelobby.runner.core.ReportedTestCase;
import pl.codelobby.runner.core.SuiteEvaluation;
import pl.codelobby.runner.core.SuiteReportException;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;

import static pl.codelobby.runner.core.SuiteEvaluation.higherPriority;

/** Combines verified engine outcomes with host reports for one validated suite. */
public final class Judge0SuiteEvaluator {

    private final SuiteReportParser parser;

    public Judge0SuiteEvaluator(SuiteReportParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** Call only after polling has reached a terminal engine state. */
    public SuiteEvaluation evaluate(TestSuite suite, Judge0Result engine, Judge0SuiteReport report) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(engine, "engine");
        if (!engine.status().finished()) {
            throw new IllegalArgumentException("Suite execution is not finished");
        }

        // Only the worker's compile-phase observation can certify this failure.
        if (report != null && report.configurationError()) {
            return results(suite, Verdict.EXECUTION_ERROR, List.of());
        }
        Verdict status = engineVerdict(engine);
        if (status == Verdict.COMPILATION_ERROR || status == Verdict.EXECUTION_ERROR) {
            return results(suite, status, List.of());
        }
        Objects.requireNonNull(report, "report");
        if (report.state() == Judge0SuiteReport.State.TOO_LARGE) {
            return results(suite, higherPriority(status, Verdict.OUTPUT_LIMIT_EXCEEDED), List.of());
        }
        if (report.state() == Judge0SuiteReport.State.MISSING) {
            return results(suite, status == Verdict.ACCEPTED ? Verdict.EXECUTION_ERROR : status, List.of());
        }

        List<String> expectedIds = suite.cases().stream().map(TestCaseDefinition::testId).toList();
        List<ReportedTestCase> cases;
        try {
            cases = status == Verdict.ACCEPTED
                    ? parser.parse(report.content(), expectedIds)
                    : parser.parseTerminated(report.content(), expectedIds);
        } catch (SuiteReportException exception) {
            return results(suite, higherPriority(status, Verdict.valueOf(exception.verdict().name())), List.of());
        }
        for (var testCase : cases) {
            status = higherPriority(status, Verdict.valueOf(testCase.status().name()));
        }
        return results(suite, status, cases);
    }

    private static Verdict engineVerdict(Judge0Result engine) {
        return switch (engine.status()) {
            case COMPILATION_ERROR -> Verdict.COMPILATION_ERROR;
            case TIME_LIMIT_EXCEEDED -> Verdict.TIME_LIMIT_EXCEEDED;
            case RUNTIME_ERROR_SIGXFSZ -> Verdict.OUTPUT_LIMIT_EXCEEDED;
            case RUNTIME_ERROR_OTHER -> "Memory limit exceeded".equals(engine.message())
                    ? Verdict.MEMORY_LIMIT_EXCEEDED : Verdict.RUNTIME_ERROR;
            case RUNTIME_ERROR_SIGSEGV, RUNTIME_ERROR_SIGFPE, RUNTIME_ERROR_SIGABRT, RUNTIME_ERROR_NZEC ->
                    Verdict.RUNTIME_ERROR;
            case ACCEPTED -> {
                if ((engine.exitCode() != null && engine.exitCode() != 0)
                        || (engine.exitSignal() != null && engine.exitSignal() != 0)) {
                    yield Verdict.RUNTIME_ERROR;
                }
                yield engine.exitCode() == null ? Verdict.EXECUTION_ERROR : Verdict.ACCEPTED;
            }
            default -> Verdict.EXECUTION_ERROR;
        };
    }

    private static SuiteEvaluation results(TestSuite suite, Verdict status, List<ReportedTestCase> reported) {
        var results = new ArrayList<TestResult>(suite.cases().size());
        for (int i = 0; i < suite.cases().size(); i++) {
            var result = i < reported.size() ? reported.get(i) : null;
            boolean expose = result != null && suite.visibility() == TestVisibility.PUBLIC;
            results.add(new TestResult(suite.cases().get(i).testId(), suite.suiteId(), suite.visibility(),
                    result == null ? TestVerdict.NOT_RUN : result.status(),
                    expose ? result.input() : null, expose ? result.expectedOutput() : null,
                    expose ? result.actualOutput() : null, null, null));
        }
        return new SuiteEvaluation(status, results);
    }
}
