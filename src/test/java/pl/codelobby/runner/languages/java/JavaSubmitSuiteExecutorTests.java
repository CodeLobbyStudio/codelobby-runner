package pl.codelobby.runner.languages.java;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.PreparedSourceFile;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0Result;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteReport;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JavaSubmitSuiteExecutorTests {

    private final SubmitRequest request = JsonMapper.builder().build().readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final Judge0Client client = mock(Judge0Client.class);
    private final Judge0SubmissionToken token = new Judge0SubmissionToken("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private final AtomicLong clock = new AtomicLong();
    private final RunRequestValidator validator = new RunRequestValidator();
    private final JavaSuitePlanner planner = new JavaSuitePlanner(validator, new SourceFileOverlay(validator));
    private final JavaSuiteArchive archive = new JavaSuiteArchive();
    private final JavaSubmitSuiteExecutor executor = new JavaSubmitSuiteExecutor(planner, archive, client,
            new Judge0SuiteEvaluator(new SuiteReportParser()), clock::get);
    private final ExecutionLimits limits = new ExecutionLimits(1000, 3000, 128000000);

    @BeforeEach
    void prepareClient() {
        when(client.submitProject(any(), any(), any())).thenReturn(token);
        when(client.awaitResult(eq(token), any())).thenReturn(engine(Judge0Status.ACCEPTED, token));
        report("hidden-1", "ACCEPTED");
    }

    @Test
    void executesHiddenSuiteAndRedactsAllDiagnosticFields() {
        var result = execute(1);
        assertThat(result.evaluation().status()).isEqualTo(Verdict.ACCEPTED);
        var test = result.evaluation().testResults().getFirst();
        assertThat(test.visibility()).isEqualTo(TestVisibility.HIDDEN);
        assertThat(test.testId()).isEqualTo("hidden-1");
        assertThat(test.input()).isNull();
        assertThat(test.expectedOutput()).isNull();
        assertThat(test.actualOutput()).isNull();
        assertThat(test.stdout()).isNull();
        assertThat(test.stderr()).isNull();
        assertThat(result.compilation()).isEqualTo(new Judge0CompilationUsage(50, 100));
        assertThat(result.cpuTimeMs()).isEqualTo(20);
        assertThat(result.peakMemoryBytes()).isEqualTo(32000000);
        assertThat(result.toString()).doesNotContain("secret-value", "raw-private");
        verify(client).submitProject(any(), eq(limits), eq(Duration.ofSeconds(30)));
    }

    @Test
    void publicSuiteCanReturnCaseDataWithoutRawEngineStreams() {
        report("public-1", "WRONG_ANSWER");
        var result = execute(0);
        var test = result.evaluation().testResults().getFirst();
        assertThat(result.evaluation().status()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(test.input().asString()).isEqualTo("secret-value");
        assertThat(test.expectedOutput().asInt()).isEqualTo(5);
        assertThat(test.actualOutput().asInt()).isEqualTo(4);
        assertThat(test.stdout()).isNull();
        assertThat(test.stderr()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Judge0Status.class, names = {"COMPILATION_ERROR", "TIME_LIMIT_EXCEEDED", "RUNTIME_ERROR_OTHER"})
    void reportsSolutionFailuresWithNoHiddenDiagnostics(Judge0Status status) {
        when(client.awaitResult(eq(token), any())).thenReturn(engine(status, token));
        when(client.getSuiteReport(eq(token), any())).thenReturn(new Judge0SuiteReport(
                Judge0SuiteReport.State.MISSING, null, new Judge0CompilationUsage(50, 100)));
        var result = execute(1);
        assertThat(result.evaluation().status()).isEqualTo(switch (status) {
            case COMPILATION_ERROR -> Verdict.COMPILATION_ERROR;
            case TIME_LIMIT_EXCEEDED -> Verdict.TIME_LIMIT_EXCEEDED;
            default -> Verdict.RUNTIME_ERROR;
        });
        assertThat(result.evaluation().testResults().getFirst().status()).isEqualTo(TestVerdict.NOT_RUN);
        if (status == Judge0Status.COMPILATION_ERROR) {
            assertThat(result.cpuTimeMs()).isNull();
        }
    }

    @Test
    void rejectsUnfinishedOrWrongTokenResultsWithoutFetchingAReport() {
        when(client.awaitResult(eq(token), any())).thenReturn(engine(Judge0Status.PROCESSING, token));
        assertThatThrownBy(() -> execute(1)).isInstanceOf(ExecutionUnavailableException.class);
        when(client.awaitResult(eq(token), any())).thenReturn(engine(Judge0Status.ACCEPTED,
                new Judge0SubmissionToken("bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee")));
        assertThatThrownBy(() -> execute(1)).isInstanceOf(ExecutionUnavailableException.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).getSuiteReport(any(), any());
    }

    @Test
    void rejectsMissingCompilerOrRuntimeMeasurements() {
        when(client.getSuiteReport(eq(token), any())).thenReturn(new Judge0SuiteReport(
                Judge0SuiteReport.State.MISSING, null));
        assertThatThrownBy(() -> execute(1)).isInstanceOf(ExecutionUnavailableException.class);
        report("hidden-1", "ACCEPTED");
        when(client.awaitResult(eq(token), any())).thenReturn(new Judge0Result(token, Judge0Status.ACCEPTED,
                null, null, null, null, null, 30.0, 32000000L, 0, null));
        assertThatThrownBy(() -> execute(1)).isInstanceOf(ExecutionUnavailableException.class);
    }

    @Test
    void invalidMetadataOrExpandedBudgetsNeverContactJudge0() {
        var invalid = new SubmitRequest("bad id", request.taskVersionId(), request.language(), request.taskFiles(),
                request.submissionFiles(), request.testSuites(), request.limits());
        assertThatThrownBy(() -> executor.execute(invalid, 1, limits, 10000, 20000, Duration.ofSeconds(30)))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> executor.execute(request, 1, new ExecutionLimits(2001, 5000, 128000000),
                10000, 20000, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execute(2)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void preservesTimeoutTokenAndNeverAutomaticallyResubmits() {
        when(client.awaitResult(eq(token), any())).thenThrow(
                new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token));
        assertThatThrownBy(() -> execute(1)).isInstanceOfSatisfying(Judge0ClientException.class,
                error -> assertThat(error.submissionToken()).isEqualTo(token));
        verify(client).submitProject(any(), any(), any());
    }

    @Test
    void timeoutImmediatelyAfterPostRetainsTheActiveToken() {
        when(client.submitProject(any(), any(), any())).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(31).toNanos());
            return token;
        });
        assertThatThrownBy(() -> execute(1)).isInstanceOfSatisfying(Judge0ClientException.class,
                error -> assertThat(error.submissionToken()).isEqualTo(token));
    }

    @Test
    void refusesExpiredBudgetBeforePosting() {
        assertThatThrownBy(() -> executor.execute(request, 1, limits, 10000, 20000, Duration.ZERO))
                .isInstanceOf(RunDeadlineExceededException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void buildsOnlyTheSelectedSuiteWithHostSourcesBeforeParticipantSources(int index) throws Exception {
        var plan = planner.prepareSubmit(request).get(index);
        byte[] zip = archive.createSubmit(plan, request.testSuites().get(index).visibility(), 8000, 12000);
        var paths = new ArrayList<String>();
        String compile = "";
        try (var stream = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                paths.add(entry.getName());
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                if (entry.getName().equals("compile")) {
                    compile = content;
                }
            }
        }
        String own = index == 0 ? "PublicTests" : "HiddenTests";
        String other = index == 0 ? "HiddenTests" : "PublicTests";
        assertThat(paths).contains("sources/tests/" + own + ".java", "sources/src/Solution.java")
                .doesNotContain("sources/tests/" + other + ".java");
        assertThat(compile).contains("# CODELOBBY_BUILD_LIMITS 8000 12000");
        assertThat(compile.indexOf("sources/tests/" + own)).isLessThan(compile.indexOf("sources/src/Solution"));
    }

    @Test
    void rejectsMixedRoleArchivesAndKeepsTheRunArchivePathPublicOnly() {
        var hidden = planner.prepareSubmit(request).getLast();
        assertThatThrownBy(() -> archive.create(hidden)).isInstanceOf(IllegalArgumentException.class);
        var files = new ArrayList<>(hidden.files());
        files.add(new PreparedSourceFile("tests/Other.java", FileType.VISIBLE_TEST, "private-test"));
        var mixed = new JavaSuitePlan(hidden.suiteId(), hidden.mainClass(), files, hidden.testIds());
        assertThatThrownBy(() -> archive.createSubmit(mixed, TestVisibility.HIDDEN, 10000, 20000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-test");
        assertThatThrownBy(() -> archive.createSubmit(hidden, TestVisibility.PUBLIC, 10000, 20000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JavaSubmitSuiteExecutor.Outcome execute(int index) {
        return executor.execute(request, index, limits, 10000, 20000, Duration.ofSeconds(30));
    }

    private Judge0Result engine(Judge0Status status, Judge0SubmissionToken resultToken) {
        return new Judge0Result(resultToken, status, "raw-private", "raw-private", "raw-private", "raw-private",
                status == Judge0Status.COMPILATION_ERROR ? null : 20.0,
                status == Judge0Status.COMPILATION_ERROR ? null : 30.0,
                status == Judge0Status.COMPILATION_ERROR ? null : 32000000L, 0, null);
    }

    private void report(String id, String status) {
        String json = "{\"testId\":\"" + id + "\",\"status\":\"" + status
                + "\",\"input\":\"secret-value\",\"expectedOutput\":5,\"actualOutput\":4}\n";
        when(client.getSuiteReport(eq(token), any())).thenReturn(new Judge0SuiteReport(
                Judge0SuiteReport.State.AVAILABLE, json.getBytes(StandardCharsets.UTF_8), new Judge0CompilationUsage(50, 100)));
    }
}
