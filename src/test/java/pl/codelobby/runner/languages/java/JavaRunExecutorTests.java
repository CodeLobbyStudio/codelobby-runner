package pl.codelobby.runner.languages.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.RunSubmissionUseCase;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0Result;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0Submission;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteReport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaRunExecutorTests {

    private final AtomicLong clock = new AtomicLong();
    private final Engine client = new Engine();
    private final RunRequestValidator validator = new RunRequestValidator();
    private final JavaRunExecutor executor = new JavaRunExecutor(
            new JavaSuitePlanner(validator, new SourceFileOverlay(validator)), new JavaSuiteArchive(),
            client, new Judge0SuiteEvaluator(new SuiteReportParser()), clock::get);

    @Test
    void runsAllSuitesWithShrinkingBudgetsAndAggregatesResultsThroughTheUseCase() throws IOException {
        client.add(Judge0Status.ACCEPTED, 100.25, 150.5, 32_000_000L, 1000.25, 2000.5, "ACCEPTED");
        client.add(Judge0Status.ACCEPTED, 300.5, 400.5, 48_000_000L, 2000, 3000, "WRONG_ANSWER");

        var response = new RunSubmissionUseCase(validator, executor).execute(request());
        var result = response.result();

        assertThat(response.requestId()).isEqualTo("request");
        assertThat(result.status()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(result.passedTests()).isEqualTo(1);
        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.testResults()).extracting(TestResult::testId).containsExactly("case-1", "case-2");
        assertThat(result.testResults()).extracting(TestResult::suiteId).containsExactly("suite-1", "suite-2");
        assertThat(result.sandboxCpuTimeMs()).isEqualTo(400.75);
        assertThat(result.sandboxPeakMemoryBytes()).isEqualTo(48_000_000L);
        assertThat(result.compileOutput()).isNull();
        assertThat(result.benchmark().state()).isEqualTo(BenchmarkState.NOT_REQUESTED);
        assertThat(client.limits).containsExactly(
                new ExecutionLimits(2000, 5000, 128_000_000),
                new ExecutionLimits(1899, 4849, 128_000_000));
        assertThat(compileScript(client.archives.get(0))).contains("# CODELOBBY_BUILD_LIMITS 10000 20000");
        assertThat(compileScript(client.archives.get(1))).contains("# CODELOBBY_BUILD_LIMITS 8999 17999");
    }

    @Test
    void continuesIndependentTargetsAfterCompilationFailureWithoutChargingRuntime() {
        client.add(Judge0Status.COMPILATION_ERROR, null, null, null, 500, 700, null);
        client.add(Judge0Status.ACCEPTED, 100.0, 200.0, 32_000_000L, 600, 900, "ACCEPTED");

        var result = executor.execute(request());

        assertThat(result.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(result.testResults()).extracting(TestResult::status)
                .containsExactly(TestVerdict.NOT_RUN, TestVerdict.ACCEPTED);
        assertThat(result.passedTests()).isEqualTo(1);
        assertThat(result.sandboxCpuTimeMs()).isEqualTo(100.0);
        assertThat(client.limits.get(1)).isEqualTo(request().limits());
    }

    @Test
    void doesNotLaunchAnotherSuiteAfterTheCpuBudgetIsSpent() {
        client.add(Judge0Status.TIME_LIMIT_EXCEEDED, 2000.0, 2500.0, 32_000_000L, 500, 900, null);

        var result = executor.execute(request());

        assertThat(client.archives).hasSize(1);
        assertThat(result.status()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
    }

    @Test
    void doesNotLaunchAnotherSuiteAfterTheWallBudgetIsSpent() {
        client.add(Judge0Status.TIME_LIMIT_EXCEEDED, 100.0, 5000.0, 32_000_000L, 500, 900, null);

        assertThat(executor.execute(request()).status()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(client.archives).hasSize(1);
    }

    @Test
    void doesNotRenewCompilationBudgetForAnotherTarget() {
        client.add(Judge0Status.COMPILATION_ERROR, null, null, null, 10_000, 15_000, null);

        var result = executor.execute(request());

        assertThat(client.archives).hasSize(1);
        assertThat(result.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
        assertThat(result.sandboxCpuTimeMs()).isNull();
        assertThat(result.sandboxPeakMemoryBytes()).isNull();
    }

    @Test
    void roundsDownTheRemainingCpuBudgetWhenWallTimeIsTheTighterLimit() {
        client.add(Judge0Status.ACCEPTED, 1.0, 4998.25, 32_000_000L, 500, 900, "ACCEPTED");
        client.add(Judge0Status.ACCEPTED, 0.1, 0.2, 32_000_000L, 500, 900, "ACCEPTED");

        executor.execute(request());

        assertThat(client.limits.get(1)).isEqualTo(new ExecutionLimits(1, 1, 128_000_000));
    }

    @Test
    void rejectsFurtherExecutionWhenCompilerMeasurementsAreUnavailable() {
        client.add(Judge0Status.ACCEPTED, 10.0, 20.0, 32_000_000L, 0, 0, "ACCEPTED");
        client.reports.set(0, new Judge0SuiteReport(Judge0SuiteReport.State.MISSING, null));

        assertThatThrownBy(() -> executor.execute(request())).isInstanceOf(ExecutionUnavailableException.class);
        assertThat(client.archives).hasSize(1);
    }

    @Test
    void rejectsFurtherExecutionWhenRuntimeMeasurementsAreUnavailable() {
        client.add(Judge0Status.ACCEPTED, null, null, null, 500, 900, "ACCEPTED");

        assertThatThrownBy(() -> executor.execute(request())).isInstanceOf(ExecutionUnavailableException.class);
        assertThat(client.archives).hasSize(1);
    }

    @Test
    void preservesAnUnconfirmedEngineFailureAndDoesNotResubmit() {
        client.failure = new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token(0));

        assertThatThrownBy(() -> executor.execute(request())).isSameAs(client.failure);
        assertThat(client.archives).hasSize(1);
    }

    @Test
    void retainsTheAcceptedTokenWhenTheDeadlineExpiresImmediatelyAfterSubmission() {
        client.onSubmit = () -> clock.set(Duration.ofSeconds(60).toNanos());

        assertThatThrownBy(() -> executor.execute(request()))
                .isInstanceOfSatisfying(Judge0ClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(Judge0ClientException.Reason.TIMEOUT);
                    assertThat(exception.submissionToken()).isEqualTo(token(0));
                });
        assertThat(client.archives).hasSize(1);
    }

    @Test
    void boundsAllCommunicationByTheSameOperationDeadline() {
        client.add(Judge0Status.ACCEPTED, 10.0, 20.0, 32_000_000L, 500, 900, "ACCEPTED");
        client.add(Judge0Status.ACCEPTED, 10.0, 20.0, 32_000_000L, 500, 900, "ACCEPTED");
        client.onAwait = () -> clock.addAndGet(Duration.ofSeconds(10).toNanos());

        executor.execute(request());

        assertThat(client.timeouts).containsExactly(
                Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(50),
                Duration.ofSeconds(50), Duration.ofSeconds(50), Duration.ofSeconds(40));
    }

    @Test
    void honorsTheShorterDeadlineRemainingAfterAdmissionQueueing() {
        client.add(Judge0Status.ACCEPTED, 10.0, 20.0, 32_000_000L, 500, 900, "ACCEPTED");
        client.onAwait = () -> clock.set(Duration.ofSeconds(5).toNanos());

        assertThatThrownBy(() -> executor.execute(request(), Duration.ofSeconds(5)))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThat(client.archives).hasSize(1);
    }

    @Test
    void stopsWhenTheOperationDeadlineExpiresEvenIfTheResultArrives() {
        client.add(Judge0Status.ACCEPTED, 10.0, 20.0, 32_000_000L, 500, 900, "ACCEPTED");
        client.onAwait = () -> clock.set(Duration.ofSeconds(60).toNanos());

        assertThatThrownBy(() -> executor.execute(request())).isInstanceOf(RunDeadlineExceededException.class);
        assertThat(client.archives).hasSize(1);
    }

    private static RunRequest request() {
        return new RunRequest("request", "version", Language.JAVA, List.of(
                new TaskFile("src/Solution.java", FileType.EDITABLE, "class Solution {}"),
                new TaskFile("tests/First.java", FileType.VISIBLE_TEST, "class First {}"),
                new TaskFile("tests/Second.java", FileType.VISIBLE_TEST, "class Second {}")), List.of(), List.of(
                new TestSuite("suite-1", TestVisibility.PUBLIC, List.of("tests/First.java"),
                        new JavaExecution("First"), List.of(new TestCaseDefinition("case-1"))),
                new TestSuite("suite-2", TestVisibility.PUBLIC, List.of("tests/Second.java"),
                        new JavaExecution("Second"), List.of(new TestCaseDefinition("case-2")))),
                new ExecutionLimits(2000, 5000, 128_000_000));
    }

    private static Judge0SubmissionToken token(int index) {
        return new Judge0SubmissionToken("d85cd024-1548-4165-96c7-%012d".formatted(index));
    }

    private static String compileScript(byte[] bytes) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("compile")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing compile script");
    }

    private static final class Engine implements Judge0Client {
        final List<Judge0Result> outcomes = new ArrayList<>();
        final List<Judge0SuiteReport> reports = new ArrayList<>();
        final List<ExecutionLimits> limits = new ArrayList<>();
        final List<byte[]> archives = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        Judge0ClientException failure;
        Runnable onAwait = () -> {};
        Runnable onSubmit = () -> {};

        void add(Judge0Status status, Double cpu, Double wall, Long memory,
                double buildCpu, double buildWall, String caseStatus) {
            int index = outcomes.size();
            outcomes.add(new Judge0Result(token(index), status, null, null, null, null,
                    cpu, wall, memory, status == Judge0Status.COMPILATION_ERROR ? null : 0, null));
            byte[] text = caseStatus == null ? null : ("{\"testId\":\"case-" + (index + 1)
                    + "\",\"status\":\"" + caseStatus + "\"}").getBytes(StandardCharsets.UTF_8);
            reports.add(new Judge0SuiteReport(text == null ? Judge0SuiteReport.State.MISSING
                    : Judge0SuiteReport.State.AVAILABLE, text, new Judge0CompilationUsage(buildCpu, buildWall)));
        }

        @Override
        public Judge0SubmissionToken submitProject(byte[] zip, ExecutionLimits budget, Duration timeout) {
            timeouts.add(timeout);
            return submitProject(zip, budget);
        }

        @Override
        public Judge0SubmissionToken submitProject(byte[] zip, ExecutionLimits budget) {
            int index = archives.size();
            archives.add(zip);
            limits.add(budget);
            onSubmit.run();
            return token(index);
        }

        @Override
        public Judge0Result awaitResult(Judge0SubmissionToken token, Duration timeout) {
            timeouts.add(timeout);
            return awaitResult(token);
        }

        @Override
        public Judge0Result awaitResult(Judge0SubmissionToken token) {
            if (failure != null) {
                throw failure;
            }
            onAwait.run();
            return outcomes.get(archives.size() - 1);
        }

        @Override
        public Judge0SuiteReport getSuiteReport(Judge0SubmissionToken token, Duration timeout) {
            timeouts.add(timeout);
            return getSuiteReport(token);
        }

        @Override
        public Judge0SuiteReport getSuiteReport(Judge0SubmissionToken token) {
            return reports.get(archives.size() - 1);
        }

        @Override
        public Judge0Result getSubmission(Judge0SubmissionToken token) {
            throw new AssertionError("Executor should use bounded polling");
        }

        @Override
        public Judge0SubmissionToken submitJava(Judge0Submission submission) {
            throw new AssertionError("Executor should submit multi-file projects");
        }
    }
}
