package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.RunResult;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunSubmissionUseCaseTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private RunRequest request;

    @BeforeEach
    void readRequest() throws Exception {
        request = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
    }

    @ParameterizedTest
    @CsvSource({
            "run-accepted.json, ACCEPTED",
            "run-wrong-answer.json, WRONG_ANSWER",
            "run-compilation-error.json, COMPILATION_ERROR"
    })
    void buildsTheCompleteContractResponseUsingTheExecutor(String example, Verdict verdict) throws Exception {
        var expectedJson = mapper.readTree(Path.of("docs/api/examples", example).toFile());
        var expected = mapper.treeToValue(expectedJson, RunResponse.class);
        var useCase = new RunSubmissionUseCase(new RunRequestValidator(),
                new StubSubmissionExecutor(expected.result()));

        var response = useCase.execute(request);

        assertThat(response.result().status()).isEqualTo(verdict);
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(expectedJson);
    }

    @Test
    void invokesTheExecutorOnceWithTheOriginalRequestAndPreservesCorrelation() throws Exception {
        var result = mapper.readValue(Path.of("docs/api/examples/run-accepted.json").toFile(), RunResponse.class).result();
        var calls = new AtomicInteger();
        var received = new AtomicReference<RunRequest>();
        SubmissionExecutor executor = input -> {
            calls.incrementAndGet();
            received.set(input);
            return result;
        };
        var useCase = new RunSubmissionUseCase(new RunRequestValidator(), executor);
        var correlated = new RunRequest("different-run", "different-task-version", request.language(),
                request.taskFiles(), request.submissionFiles(), request.testSuites(), request.limits());

        var response = useCase.execute(correlated);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(received.get()).isSameAs(correlated);
        assertThat(response.requestId()).isEqualTo("different-run");
        assertThat(response.taskVersionId()).isEqualTo("different-task-version");
        assertThat(response.result()).isSameAs(result);
    }

    @Test
    void rejectsInvalidMetadataFilesAndSuitesBeforeCallingTheExecutor() {
        var calls = new AtomicInteger();
        var useCase = new RunSubmissionUseCase(new RunRequestValidator(), input -> {
            calls.incrementAndGet();
            throw new AssertionError("Invalid requests must never reach execution");
        });
        var invalidId = new RunRequest("bad id", request.taskVersionId(), request.language(), request.taskFiles(),
                request.submissionFiles(), request.testSuites(), request.limits());
        var invalidFiles = new RunRequest(request.requestId(), request.taskVersionId(), request.language(), request.taskFiles(),
                List.of(new SubmissionFile("tests/PublicTests.java", "replacement")), request.testSuites(), request.limits());
        var invalidSuites = new RunRequest(request.requestId(), request.taskVersionId(), request.language(), request.taskFiles(),
                request.submissionFiles(), List.of(), request.limits());

        for (var invalid : List.of(invalidId, invalidFiles, invalidSuites)) {
            assertThatThrownBy(() -> useCase.execute(invalid)).isInstanceOf(RequestValidationException.class);
        }
        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(RequestValidationException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void propagatesExecutorFailureWithoutReturningSuccessfulFeedback() {
        var failure = new IllegalStateException("Simulated executor failure");
        var calls = new AtomicInteger();
        var useCase = new RunSubmissionUseCase(new RunRequestValidator(), input -> {
            calls.incrementAndGet();
            throw failure;
        });

        assertThatThrownBy(() -> useCase.execute(request)).isSameAs(failure);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsMissingExecutorResult() {
        var useCase = new RunSubmissionUseCase(new RunRequestValidator(), input -> null);

        assertThatThrownBy(() -> useCase.execute(request)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Executor did not return a result");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-id", "wrong-suite", "missing", "extra", "reordered", "duplicate"})
    void rejectsResultsNotMatchingDeclaredCases(String defect) throws Exception {
        var suite = request.testSuites().getFirst();
        var multiple = new TestSuite(suite.suiteId(), suite.visibility(), suite.testFilePaths(), suite.execution(),
                List.of(new TestCaseDefinition("first"), new TestCaseDefinition("second")));
        var input = new RunRequest(request.requestId(), request.taskVersionId(), request.language(), request.taskFiles(),
                request.submissionFiles(), List.of(multiple), request.limits());
        var first = acceptedCase("first", suite.suiteId());
        var second = acceptedCase("second", suite.suiteId());
        var cases = switch (defect) {
            case "wrong-id" -> List.of(first, acceptedCase("unexpected", suite.suiteId()));
            case "wrong-suite" -> List.of(first, acceptedCase("second", "other-suite"));
            case "missing" -> List.of(first);
            case "extra" -> List.of(first, second, acceptedCase("extra", suite.suiteId()));
            case "reordered" -> List.of(second, first);
            case "duplicate" -> List.of(first, first);
            default -> throw new AssertionError("Unknown test defect");
        };
        var template = mapper.readValue(Path.of("docs/api/examples/run-accepted.json").toFile(), RunResponse.class).result();
        var result = new RunResult(Verdict.ACCEPTED, cases.size(), cases.size(), cases, "", null, null, null,
                template.benchmark(), template.gradingIntegrity());
        var useCase = new RunSubmissionUseCase(new RunRequestValidator(), new StubSubmissionExecutor(result));

        assertThatThrownBy(() -> useCase.execute(input)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Executor result does not match the declared tests");
    }

    private TestResult acceptedCase(String id, String suiteId) {
        return new TestResult(id, suiteId, TestVisibility.PUBLIC, TestVerdict.ACCEPTED,
                null, null, null, null, null);
    }
}
