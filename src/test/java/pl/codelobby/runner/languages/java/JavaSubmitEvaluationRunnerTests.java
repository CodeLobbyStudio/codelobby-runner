package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SuiteEvaluation;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JavaSubmitEvaluationRunnerTests {

    private final SubmitRequest request = JsonMapper.builder().build().readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final JavaSubmitSuiteExecutor executor = mock(JavaSubmitSuiteExecutor.class);
    private final AtomicLong clock = new AtomicLong();
    private final JavaSubmitEvaluationRunner runner = new JavaSubmitEvaluationRunner(executor, clock::get);

    @Test
    void sharesBuildAndRuntimeBudgetsAndPreservesAllCasesInOrder() {
        stub(0, outcome(0, Verdict.ACCEPTED, 100.25, 150.5, 32000000L, 1000.25, 2000.5));
        stub(1, outcome(1, Verdict.WRONG_ANSWER, 300.5, 400.5, 48000000L, 2000, 3000));

        var summary = runner.evaluate(request, Duration.ofSeconds(30));

        verify(executor).execute(request, 0, request.limits(), 10000, 20000, Duration.ofSeconds(30));
        verify(executor).execute(request, 1, new ExecutionLimits(1899, 4849, 128000000),
                8999, 17999, Duration.ofSeconds(30));
        assertThat(summary.status()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(summary.passedTests()).isEqualTo(1);
        assertThat(summary.totalTests()).isEqualTo(2);
        assertThat(summary.testResults()).extracting(TestResult::testId).containsExactly("public-1", "hidden-1");
        assertThat(summary.sandboxCpuTimeMs()).isEqualTo(400.75);
        assertThat(summary.sandboxWallTimeMs()).isEqualTo(551);
        assertThat(summary.sandboxPeakMemoryBytes()).isEqualTo(48000000);
        assertThat(summary.compilation()).isEqualTo(new Judge0CompilationUsage(3000.25, 5000.5));
        assertThatThrownBy(() -> summary.testResults().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void continuesIndependentTargetsAfterACompilationFailureWithoutChargingRuntime() {
        stub(0, outcome(0, Verdict.COMPILATION_ERROR, null, null, null, 500, 700));
        stub(1, outcome(1, Verdict.ACCEPTED, 100.0, 200.0, 32000000L, 600, 900));
        var summary = runner.evaluate(request, Duration.ofSeconds(30));
        verify(executor).execute(request, 1, request.limits(), 9500, 19300, Duration.ofSeconds(30));
        assertThat(summary.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(summary.passedTests()).isEqualTo(1);
        assertThat(summary.testResults()).extracting(TestResult::status)
                .containsExactly(TestVerdict.NOT_RUN, TestVerdict.ACCEPTED);
        assertThat(summary.sandboxCpuTimeMs()).isEqualTo(100);
    }

    @ParameterizedTest
    @CsvSource({"2000,10", "1999.5,10", "10,5000", "10,4999.5"})
    void doesNotLaunchMoreSuitesWhenRuntimeBudgetCannotSupplyOneMillisecond(double cpu, double wall) {
        stub(0, outcome(0, Verdict.ACCEPTED, cpu, wall, 32000000L, 10, 20));
        var summary = runner.evaluate(request, Duration.ofSeconds(30));
        verifyNoSecondSuite();
        assertThat(summary.status()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(summary.testResults().getLast().status()).isEqualTo(TestVerdict.NOT_RUN);
        assertThat(summary.testResults().getLast().input()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"10000,10", "9999.5,10", "10,20000", "10,19999.5"})
    void doesNotLaunchMoreSuitesWhenCompilationBudgetIsExhausted(double cpu, double wall) {
        stub(0, outcome(0, Verdict.COMPILATION_ERROR, null, null, null, cpu, wall));
        var summary = runner.evaluate(request, Duration.ofSeconds(30));
        verifyNoSecondSuite();
        assertThat(summary.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(summary.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
        assertThat(summary.sandboxCpuTimeMs()).isNull();
        assertThat(summary.sandboxPeakMemoryBytes()).isNull();
    }

    @Test
    void capsEachSandboxMemoryAndReportsThePeakRatherThanSummingIt() {
        stub(0, outcome(0, Verdict.ACCEPTED, 10.0, 20.0, 80000000L, 10, 20));
        stub(1, outcome(1, Verdict.ACCEPTED, 10.0, 20.0, 90000000L, 10, 20));
        var summary = runner.evaluate(request, Duration.ofSeconds(30));
        assertThat(summary.status()).isEqualTo(Verdict.ACCEPTED);
        assertThat(summary.sandboxPeakMemoryBytes()).isEqualTo(90000000);
        verify(executor).execute(eq(request), eq(1), eq(new ExecutionLimits(1990, 4980, 128000000)),
                anyInt(), anyInt(), any());
    }

    @ParameterizedTest
    @CsvSource({"2001,10,32000000,TIME_LIMIT_EXCEEDED", "10,5001,32000000,TIME_LIMIT_EXCEEDED",
            "10,20,128000001,MEMORY_LIMIT_EXCEEDED"})
    void engineMeasurementsCanOverrideAnAcceptedReport(double cpu, double wall, long memory, Verdict expected) {
        stub(0, outcome(0, Verdict.ACCEPTED, cpu, wall, memory, 10, 20));
        stub(1, outcome(1, Verdict.ACCEPTED, 1.0, 1.0, 32000000L, 10, 20));
        assertThat(runner.evaluate(request, Duration.ofSeconds(30)).status()).isEqualTo(expected);
    }

    @Test
    void compilationOverrunHasContractPriorityEvenAfterAcceptedReports() {
        stub(0, outcome(0, Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 10000.1, 20));
        assertThat(runner.evaluate(request, Duration.ofSeconds(30)).status()).isEqualTo(Verdict.COMPILATION_ERROR);
        verifyNoSecondSuite();
    }

    @Test
    void queueWaitAndPreviousSuitesReduceTheDeadlinePassedToLaterSuites() {
        when(executor.execute(eq(request), eq(0), any(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(4).toNanos());
            return outcome(0, Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 10, 20);
        });
        stub(1, outcome(1, Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 10, 20));
        runner.evaluate(request, Duration.ofSeconds(12));
        verify(executor).execute(eq(request), eq(1), any(), anyInt(), anyInt(), eq(Duration.ofSeconds(8)));
    }

    @Test
    void deadlineAfterASuiteStopsThePipelineInsteadOfReturningPartialSuccess() {
        when(executor.execute(eq(request), eq(0), any(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(31).toNanos());
            return outcome(0, Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 10, 20);
        });
        assertThatThrownBy(() -> runner.evaluate(request, Duration.ofSeconds(30)))
                .isInstanceOf(RunDeadlineExceededException.class);
        verifyNoSecondSuite();
    }

    @Test
    void transportFailureRetainsTheTokenAndNeverStartsAnotherSuite() {
        var token = new Judge0SubmissionToken("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(executor.execute(eq(request), eq(0), any(), anyInt(), anyInt(), any()))
                .thenThrow(new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token));
        assertThatThrownBy(() -> runner.evaluate(request, Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(Judge0ClientException.class,
                        failure -> assertThat(failure.submissionToken()).isEqualTo(token));
        verifyNoSecondSuite();
    }

    @Test
    void validatesBeforeAnyExecutionAndRejectsAnExpiredDeadline() {
        var invalid = new SubmitRequest("bad id", request.taskVersionId(), request.language(), request.taskFiles(),
                request.submissionFiles(), request.testSuites(), request.limits());
        assertThatThrownBy(() -> runner.evaluate(invalid, Duration.ofSeconds(30)))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> runner.evaluate(request, Duration.ZERO)).isInstanceOf(RunDeadlineExceededException.class);
        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "wrong-suite", "missing-metrics", "nonfinite"})
    void refusesInconsistentInternalOutcomes(String defect) {
        var good = outcome(0, Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 10, 20);
        var invalid = switch (defect) {
            case "null" -> null;
            case "wrong-suite" -> outcome(1, Verdict.ACCEPTED, 10.0, 20.0, 32000000L, 10, 20);
            case "missing-metrics" -> new JavaSubmitSuiteExecutor.Outcome(good.evaluation(), good.compilation(),
                    null, null, null);
            default -> new JavaSubmitSuiteExecutor.Outcome(good.evaluation(), good.compilation(),
                    Double.NaN, 20.0, 32000000L);
        };
        stub(0, invalid);
        assertThatThrownBy(() -> runner.evaluate(request, Duration.ofSeconds(30)))
                .isInstanceOf(ExecutionUnavailableException.class);
        verifyNoSecondSuite();
    }

    private void stub(int index, JavaSubmitSuiteExecutor.Outcome outcome) {
        when(executor.execute(eq(request), eq(index), any(), anyInt(), anyInt(), any())).thenReturn(outcome);
    }

    private void verifyNoSecondSuite() {
        verify(executor, never()).execute(any(), eq(1), any(), anyInt(), anyInt(), any());
    }

    private JavaSubmitSuiteExecutor.Outcome outcome(int index, Verdict verdict, Double cpu, Double wall,
            Long memory, double buildCpu, double buildWall) {
        var suite = request.testSuites().get(index);
        TestVerdict caseVerdict = verdict == Verdict.ACCEPTED ? TestVerdict.ACCEPTED
                : verdict == Verdict.WRONG_ANSWER ? TestVerdict.WRONG_ANSWER : TestVerdict.NOT_RUN;
        var cases = suite.cases().stream().map(test -> new TestResult(test.testId(), suite.suiteId(), suite.visibility(),
                caseVerdict, null, null, null, null, null)).toList();
        return new JavaSubmitSuiteExecutor.Outcome(new SuiteEvaluation(verdict, cases),
                new Judge0CompilationUsage(buildCpu, buildWall), cpu, wall, memory);
    }
}
