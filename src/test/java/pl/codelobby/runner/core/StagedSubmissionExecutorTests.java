package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.RunResult;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedSubmissionExecutorTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private RunRequest request;

    @BeforeEach
    void readRequest() throws Exception {
        request = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-accepted.json", "run-wrong-answer.json"})
    void passesBuildAndReportThroughStagesInOrder(String example) throws Exception {
        var events = new ArrayList<String>();
        var build = new CompilationResult(true, "opaque-artifact", null);
        var report = new ExecutionReport("unparsed test report", 0);
        var expected = result(example);
        var executor = new StagedSubmissionExecutor((input, suite) -> {
            events.add("compile");
            assertThat(input).isSameAs(request);
            assertThat(suite).isSameAs(request.testSuites().getFirst());
            return build;
        }, (input, suite, compilation) -> {
            events.add("run");
            assertThat(compilation).isSameAs(build);
            return report;
        }, (input, suites) -> {
            events.add("evaluate");
            assertThat(suites).containsExactly(new SuiteExecution("public-suite", build, report));
            assertThatThrownBy(() -> suites.clear()).isInstanceOf(UnsupportedOperationException.class);
            return expected;
        });

        var response = new RunSubmissionUseCase(new RunRequestValidator(), executor).execute(request);

        assertThat(response.result()).isSameAs(expected);
        assertThat(events).containsExactly("compile", "run", "evaluate");
    }

    @Test
    void failedCompilationSkipsExecutionButStillReachesEvaluation() throws Exception {
        var expected = result("run-compilation-error.json");
        var build = new CompilationResult(false, null, "internal compiler diagnostics");
        var executor = new StagedSubmissionExecutor((input, suite) -> build,
                (input, suite, compilation) -> {
                    throw new AssertionError("A failed target must not execute");
                }, (input, suites) -> {
                    assertThat(suites).containsExactly(new SuiteExecution("public-suite", build, null));
                    return expected;
                });

        var actual = executor.execute(request);

        assertThat(actual.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(actual.testResults()).extracting(TestResult::status).containsExactly(TestVerdict.NOT_RUN);
    }

    @Test
    void aFailedTargetDoesNotPreventAnIndependentTargetFromRunning() throws Exception {
        var files = new ArrayList<>(request.taskFiles());
        files.add(new TaskFile("tests/Second.java", FileType.VISIBLE_TEST, "test-only source"));
        var second = new TestSuite("second", TestVisibility.PUBLIC, List.of("tests/Second.java"),
                new JavaExecution("Second"), List.of(new TestCaseDefinition("second-case")));
        var multiple = new RunRequest(request.requestId(), request.taskVersionId(), request.language(), files,
                request.submissionFiles(), List.of(request.testSuites().getFirst(), second), request.limits());
        var events = new ArrayList<String>();
        var template = result("run-compilation-error.json");
        var accepted = new TestResult("second-case", "second", TestVisibility.PUBLIC, TestVerdict.ACCEPTED,
                null, null, null, null, null);
        var expected = new RunResult(Verdict.COMPILATION_ERROR, 1, 2,
                List.of(template.testResults().getFirst(), accepted), template.compileOutput(), null,
                null, null, template.benchmark(), template.gradingIntegrity());
        var executor = new StagedSubmissionExecutor((input, suite) -> {
            events.add("compile:" + suite.suiteId());
            return suite.suiteId().equals("second")
                    ? new CompilationResult(true, "second-artifact", null)
                    : new CompilationResult(false, null, "internal error");
        }, (input, suite, compilation) -> {
            events.add("run:" + suite.suiteId());
            return new ExecutionReport("raw report", 0);
        }, (input, suites) -> {
            events.add("evaluate");
            assertThat(suites).extracting(SuiteExecution::suiteId).containsExactly("public-suite", "second");
            assertThat(suites.getFirst().execution()).isNull();
            assertThat(suites.get(1).execution()).isNotNull();
            return expected;
        });

        var response = new RunSubmissionUseCase(new RunRequestValidator(), executor).execute(multiple);

        assertThat(response.result()).isSameAs(expected);
        assertThat(events).containsExactly("compile:public-suite", "compile:second", "run:second", "evaluate");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void propagatesInfrastructureFailuresAndDoesNotCallLaterStages(int failingStage) throws Exception {
        var expected = result("run-accepted.json");
        var events = new ArrayList<String>();
        var failure = new IllegalStateException("simulated infrastructure failure");
        var executor = new StagedSubmissionExecutor((input, suite) -> {
            events.add("compile");
            if (failingStage == 0) throw failure;
            return new CompilationResult(true, "artifact", null);
        }, (input, suite, compilation) -> {
            events.add("run");
            if (failingStage == 1) throw failure;
            return new ExecutionReport("raw report", 0);
        }, (input, suites) -> {
            events.add("evaluate");
            if (failingStage == 2) throw failure;
            return expected;
        });

        assertThatThrownBy(() -> executor.execute(request)).isSameAs(failure);
        assertThat(events).containsExactlyElementsOf(List.of("compile", "run", "evaluate").subList(0, failingStage + 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsMissingStageOutcomes(int missingStage) throws Exception {
        var expected = result("run-accepted.json");
        var events = new ArrayList<String>();
        var executor = new StagedSubmissionExecutor((input, suite) -> {
            events.add("compile");
            return missingStage == 0 ? null : new CompilationResult(true, "artifact", null);
        }, (input, suite, compilation) -> {
            events.add("run");
            return missingStage == 1 ? null : new ExecutionReport("raw report", 0);
        }, (input, suites) -> {
            events.add("evaluate");
            return missingStage == 2 ? null : expected;
        });

        assertThatThrownBy(() -> executor.execute(request)).isInstanceOf(IllegalStateException.class);
        assertThat(events).hasSize(missingStage + 1);
    }

    private RunResult result(String file) throws Exception {
        return mapper.readValue(Path.of("docs/api/examples", file).toFile(), RunResponse.class).result();
    }
}
