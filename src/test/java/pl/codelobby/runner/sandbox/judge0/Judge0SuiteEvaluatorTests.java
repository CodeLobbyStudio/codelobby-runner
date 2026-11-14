package pl.codelobby.runner.sandbox.judge0;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Judge0SuiteEvaluatorTests {

    private final Judge0SuiteEvaluator evaluator = new Judge0SuiteEvaluator(new SuiteReportParser());

    @Test
    void buildsOrderedResultsUsingTrustedSuiteMetadataAndPublicCaseData() {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(Judge0Status.ACCEPTED, 0, null),
                report(record("first", "WRONG_ANSWER") + "\n" + record("second", "ACCEPTED")));

        assertThat(result.status()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(result.testResults()).extracting(TestResult::testId).containsExactly("first", "second");
        assertThat(result.testResults()).extracting(TestResult::suiteId).containsOnly("suite");
        assertThat(result.testResults()).extracting(TestResult::visibility).containsOnly(TestVisibility.PUBLIC);
        assertThat(result.testResults()).extracting(TestResult::status)
                .containsExactly(TestVerdict.WRONG_ANSWER, TestVerdict.ACCEPTED);
        assertThat(result.testResults().getFirst().input().asInt()).isEqualTo(7);
        assertThat(result.testResults().getFirst().expectedOutput().asInt()).isEqualTo(8);
        assertThat(result.testResults().getFirst().actualOutput().asInt()).isEqualTo(9);
        assertThat(result.testResults()).allSatisfy(test -> {
            assertThat(test.stdout()).isNull();
            assertThat(test.stderr()).isNull();
        });
    }

    @Test
    void acceptsOnlyACompleteReportAfterSuccessfulExecution() {
        var accepted = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(Judge0Status.ACCEPTED, 0, null),
                report(record("first", "ACCEPTED") + "\n" + record("second", "ACCEPTED")));
        assertThat(accepted.status()).isEqualTo(Verdict.ACCEPTED);

        for (String text : List.of(
                record("first", "ACCEPTED"),
                record("first", "ACCEPTED") + "\n{\"testId\":",
                record("second", "ACCEPTED") + "\n" + record("first", "ACCEPTED"))) {
            var invalid = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(Judge0Status.ACCEPTED, 0, null),
                    report(text));
            assertThat(invalid.status()).isEqualTo(Verdict.EXECUTION_ERROR);
            assertThat(invalid.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "TIME_LIMIT_EXCEEDED, TIME_LIMIT_EXCEEDED",
            "RUNTIME_ERROR_SIGXFSZ, OUTPUT_LIMIT_EXCEEDED",
            "RUNTIME_ERROR_SIGSEGV, RUNTIME_ERROR",
            "RUNTIME_ERROR_SIGFPE, RUNTIME_ERROR",
            "RUNTIME_ERROR_SIGABRT, RUNTIME_ERROR",
            "RUNTIME_ERROR_NZEC, RUNTIME_ERROR",
            "RUNTIME_ERROR_OTHER, RUNTIME_ERROR"
    })
    void preservesVerifiedFailuresDespiteACompleteAcceptedReport(Judge0Status engineStatus, Verdict expected) {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(engineStatus, 1, null),
                report(record("first", "ACCEPTED") + "\n" + record("second", "ACCEPTED")));

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.ACCEPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\n{\"testId\":"})
    void marksTheUnreportedTailNotRunAfterVerifiedTermination(String suffix) {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.RUNTIME_ERROR_NZEC, 1, null),
                report(record("first", "ACCEPTED") + suffix));

        assertThat(result.status()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(result.testResults()).extracting(TestResult::status)
                .containsExactly(TestVerdict.ACCEPTED, TestVerdict.NOT_RUN);
    }

    @Test
    void cannotCreditALaterRecordAfterAnInvalidRecordInATerminatedReport() {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.TIME_LIMIT_EXCEEDED, 0, null),
                report(record("unknown", "ACCEPTED") + "\n" + record("second", "ACCEPTED")));

        assertThat(result.status()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
    }

    @Test
    void appliesReportValueLimitsEvenAfterFatalExecution() {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.RUNTIME_ERROR_NZEC, 1, null),
                report("{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"input\":\""
                        + "a".repeat(100_000) + "\"}"));
        assertThat(result.status()).isEqualTo(Verdict.OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void keepsValidPrefixWhenTimeoutCutsOffTheNextRecord() {
        byte[] prefix = utf8(record("first", "ACCEPTED") + "\n{\"testId\":\"second\",\"input\":\"");
        byte[] truncated = java.util.Arrays.copyOf(prefix, prefix.length + 1);
        truncated[prefix.length] = (byte) 0xc5;

        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.TIME_LIMIT_EXCEEDED, 0, null),
                new Judge0SuiteReport(Judge0SuiteReport.State.AVAILABLE, truncated));

        assertThat(result.status()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(result.testResults()).extracting(TestResult::status)
                .containsExactly(TestVerdict.ACCEPTED, TestVerdict.NOT_RUN);
    }

    @ParameterizedTest
    @EnumSource(value = Judge0SuiteReport.State.class, names = {"MISSING", "TOO_LARGE"})
    void handlesMissingAndOversizedReportsAfterSuccess(Judge0SuiteReport.State state) {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(Judge0Status.ACCEPTED, 0, null),
                new Judge0SuiteReport(state, null));

        assertThat(result.status()).isEqualTo(state == Judge0SuiteReport.State.MISSING
                ? Verdict.EXECUTION_ERROR : Verdict.OUTPUT_LIMIT_EXCEEDED);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
    }

    @Test
    void retainsTimeoutWithoutAReportAndUsesOutputLimitPriorityForAnOversizedReport() {
        var timeout = engine(Judge0Status.TIME_LIMIT_EXCEEDED, 0, null);
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC), timeout,
                new Judge0SuiteReport(Judge0SuiteReport.State.MISSING, null)).status())
                .isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC), timeout,
                new Judge0SuiteReport(Judge0SuiteReport.State.TOO_LARGE, null)).status())
                .isEqualTo(Verdict.OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void usesOnlyThePinnedEngineOomEvidenceForAMemoryLimitVerdict() {
        var missing = new Judge0SuiteReport(Judge0SuiteReport.State.MISSING, null);
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.RUNTIME_ERROR_OTHER, 1, "Memory limit exceeded"), missing).status())
                .isEqualTo(Verdict.MEMORY_LIMIT_EXCEEDED);
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.RUNTIME_ERROR_OTHER, 137, "OutOfMemoryError"), missing).status())
                .isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.RUNTIME_ERROR_NZEC, 137, "Memory limit exceeded"), missing).status())
                .isEqualTo(Verdict.RUNTIME_ERROR);
    }

    @Test
    void rejectsAcceptanceWhenExitCodeIsNonzeroOrUnknown() {
        var report = report(record("first", "ACCEPTED") + "\n" + record("second", "ACCEPTED"));
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.ACCEPTED, 1, null), report).status()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.ACCEPTED, null, null), report).status()).isEqualTo(Verdict.EXECUTION_ERROR);
    }

    @Test
    void redactsHiddenCaseDataAndRawEngineDiagnostics() {
        var result = evaluator.evaluate(suite(TestVisibility.HIDDEN), engine(Judge0Status.ACCEPTED, 0, null),
                report(record("first", "ACCEPTED") + "\n" + record("second", "ACCEPTED")));

        assertThat(result.testResults()).allSatisfy(test -> {
            assertThat(test.visibility()).isEqualTo(TestVisibility.HIDDEN);
            assertThat(test.input()).isNull();
            assertThat(test.expectedOutput()).isNull();
            assertThat(test.actualOutput()).isNull();
            assertThat(test.stdout()).isNull();
            assertThat(test.stderr()).isNull();
        });
        assertThat(result.toString()).doesNotContain("private", "first", "second");
    }

    @Test
    void reportsVerifiedHostConfigurationFailuresWithoutBlamingTheParticipant() {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.COMPILATION_ERROR, null, null),
                new Judge0SuiteReport(Judge0SuiteReport.State.MISSING, null, null, true));
        assertThat(result.status()).isEqualTo(Verdict.EXECUTION_ERROR);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
    }

    @Test
    void marksAllCasesNotRunWhenCompilationFails() {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC),
                engine(Judge0Status.COMPILATION_ERROR, null, null), null);

        assertThat(result.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
        assertThat(result.toString()).doesNotContain("private");
    }

    @ParameterizedTest
    @EnumSource(value = Judge0Status.class, names = {"IN_QUEUE", "PROCESSING"})
    void requiresACompletedEngineOutcome(Judge0Status status) {
        assertThatThrownBy(() -> evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(status, null, null), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Suite execution is not finished");
    }

    @ParameterizedTest
    @EnumSource(value = Judge0Status.class, names = {"WRONG_ANSWER", "INTERNAL_ERROR", "EXEC_FORMAT_ERROR"})
    void treatsUnexpectedEngineStatesAsExecutionErrors(Judge0Status status) {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(status, 0, null), null);
        assertThat(result.status()).isEqualTo(Verdict.EXECUTION_ERROR);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
    }

    @Test
    void keepsHostRuntimeFailureAboveWrongAnswer() {
        var result = evaluator.evaluate(suite(TestVisibility.PUBLIC), engine(Judge0Status.ACCEPTED, 0, null),
                report(record("first", "WRONG_ANSWER") + "\n" + record("second", "RUNTIME_ERROR")));
        assertThat(result.status()).isEqualTo(Verdict.RUNTIME_ERROR);
    }

    private static TestSuite suite(TestVisibility visibility) {
        return new TestSuite("suite", visibility, List.of("tests/Tests.java"), new JavaExecution("Tests"),
                List.of(new TestCaseDefinition("first"), new TestCaseDefinition("second")));
    }

    private static Judge0Result engine(Judge0Status status, Integer exitCode, String message) {
        return new Judge0Result(new Judge0SubmissionToken("d85cd024-1548-4165-96c7-7bc88673f194"), status,
                "private stdout", "private stderr", "private test source", message,
                null, null, null, exitCode, null);
    }

    private static Judge0SuiteReport report(String content) {
        return new Judge0SuiteReport(Judge0SuiteReport.State.AVAILABLE, utf8(content));
    }

    private static byte[] utf8(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static String record(String id, String status) {
        return "{\"testId\":\"" + id + "\",\"status\":\"" + status
                + "\",\"input\":7,\"expectedOutput\":8,\"actualOutput\":9}";
    }
}
