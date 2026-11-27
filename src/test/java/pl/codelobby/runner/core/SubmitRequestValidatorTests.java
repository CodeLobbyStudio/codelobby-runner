package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.CppExecution;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.PythonExecution;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitRequestValidatorTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest original = mapper.readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final SubmitRequestValidator validator = new SubmitRequestValidator();

    @Test
    void acceptsCompletePublicAndHiddenManifestButRunStillRejectsIt() {
        assertThatCode(() -> validator.validate(original)).doesNotThrowAnyException();
        var run = new RunRequest(original.submissionId(), original.taskVersionId(), original.language(),
                original.taskFiles(), original.submissionFiles(), original.testSuites(), original.limits());
        assertThatThrownBy(() -> new RunRequestValidator().validate(run))
                .isInstanceOf(RequestValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-request.json", "run-request-cpp.json", "run-request-python.json"})
    void acceptsPublicOnlyTasksForEachContractLanguage(String file) throws Exception {
        var run = mapper.readValue(Path.of("docs/api/examples", file).toFile(), RunRequest.class);
        var submit = new SubmitRequest("job", run.taskVersionId(), run.language(), run.taskFiles(),
                List.of(), run.testSuites(), run.limits());
        assertThatCode(() -> validator.validate(submit)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../secret", "has space", "ż", "has\nnewline"})
    void rejectsInvalidSubmissionAndTaskVersionIds(String id) {
        var draft = new Draft();
        draft.id = id;
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.version = id;
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void checksIdentifierLengthBoundariesAndNullRequest() {
        var draft = new Draft();
        draft.id = "a".repeat(128);
        validator.validate(draft.request());
        draft.id += "a";
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void rejectsMissingRequiredCollectionsAndMetadata() {
        var draft = new Draft();
        draft.files = null;
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
        draft = new Draft();
        draft.overrides = null;
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
        draft = new Draft();
        draft.suites = null;
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.language = null;
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.limits = null;
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsNullElementsWithoutLeakingRequestData() {
        var draft = new Draft();
        draft.files = Arrays.asList((TaskFile) null);
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
        draft = new Draft();
        draft.overrides = Arrays.asList((SubmissionFile) null);
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
        draft = new Draft();
        draft.suites = Arrays.asList((TestSuite) null);
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.suites.set(0, suite(TestVisibility.PUBLIC, List.of("tests/PublicTests.java"),
                Arrays.asList((TestCaseDefinition) null)));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/Helpers.java", "tests/PublicTests.java", "tests/HiddenTests.java", "new.java"})
    void rejectsOverridesOfProtectedOrUndeclaredFiles(String path) {
        var draft = new Draft();
        draft.overrides = List.of(new SubmissionFile(path, "private-source"));
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.java", "/absolute.java", "src/CON.java", "src//Bad.java"})
    void rejectsUnsafePaths(String path) {
        var draft = new Draft();
        draft.files.set(0, new TaskFile(path, FileType.EDITABLE, "private-source"));
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @Test
    void rejectsCollidingPathsAndInvalidSourceExtensions() {
        var draft = new Draft();
        draft.files.add(new TaskFile("SRC/solution.java", FileType.EDITABLE, ""));
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
        draft = new Draft();
        draft.files.add(new TaskFile("src/Other.py", FileType.READ_ONLY, ""));
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @Test
    void matchesTestFileRolesToSuiteVisibilityAndRequiresEveryTestFileOnce() {
        var draft = new Draft();
        draft.suites.set(0, suite(TestVisibility.PUBLIC, List.of("tests/HiddenTests.java"),
                List.of(new TestCaseDefinition("public-1"))));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.suites.set(1, suite(TestVisibility.HIDDEN, List.of("tests/PublicTests.java"),
                List.of(new TestCaseDefinition("hidden-1"))));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.suites.removeLast();
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.suites.set(0, suite(TestVisibility.PUBLIC,
                List.of("tests/PublicTests.java", "tests/PublicTests.java"),
                List.of(new TestCaseDefinition("public-1"))));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.suites.set(0, suite(TestVisibility.PUBLIC, List.of("tests/Missing.java"),
                List.of(new TestCaseDefinition("public-1"))));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsPublicSuitesAfterHiddenSuitesAndDuplicateCaseOrSuiteIds() {
        var draft = new Draft();
        Collections.reverse(draft.suites);
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        var hidden = draft.suites.getLast();
        draft.suites.set(1, new TestSuite("public-suite", hidden.visibility(), hidden.testFilePaths(),
                hidden.execution(), hidden.cases()));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        draft = new Draft();
        draft.suites.set(1, suite(TestVisibility.HIDDEN, hidden.testFilePaths(),
                List.of(new TestCaseDefinition("public-1"))));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsMissingSuiteFieldsAndMismatchedExecutionProfiles() {
        var draft = new Draft();
        draft.suites.set(0, suite(null, List.of("tests/PublicTests.java"),
                List.of(new TestCaseDefinition("case"))));
        rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        for (var profile : List.of(new JavaExecution("bad class"),
                new CppExecution("tests/PublicTests.java"), new PythonExecution("tests/PublicTests.java"))) {
            draft = new Draft();
            var first = draft.suites.getFirst();
            draft.suites.set(0, new TestSuite(first.suiteId(), first.visibility(), first.testFilePaths(),
                    profile, first.cases()));
            rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void enforcesAggregateCaseAndSuiteLimits() {
        var draft = new Draft();
        draft.suites.set(0, suite(TestVisibility.PUBLIC, List.of("tests/PublicTests.java"),
                IntStream.range(0, 49).mapToObj(i -> new TestCaseDefinition("case-" + i)).toList()));
        validator.validate(draft.request());
        draft.suites.set(0, suite(TestVisibility.PUBLIC, List.of("tests/PublicTests.java"),
                IntStream.range(0, 50).mapToObj(i -> new TestCaseDefinition("case-" + i)).toList()));
        rejects(draft, ValidationErrorCode.PAYLOAD_TOO_LARGE);
        draft = new Draft();
        draft.suites = Collections.nCopies(51, original.testSuites().getFirst());
        rejects(draft, ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void enforcesUtf8FileAndEffectiveProjectSizeLimits() {
        var draft = new Draft();
        draft.overrides = List.of(new SubmissionFile("src/Solution.java", "ą".repeat(65536)));
        validator.validate(draft.request());
        draft.overrides = List.of(new SubmissionFile("src/Solution.java", "ą".repeat(65537)));
        rejects(draft, ValidationErrorCode.PAYLOAD_TOO_LARGE);
        draft = new Draft();
        for (int i = 0; i < 8; i++) {
            draft.files.add(new TaskFile("src/Large" + i + ".java", FileType.READ_ONLY, "a".repeat(131072)));
        }
        rejects(draft, ValidationErrorCode.PAYLOAD_TOO_LARGE);
        draft = new Draft();
        draft.overrides = List.of(new SubmissionFile("src/Solution.java", "\uD800"));
        rejects(draft, ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @Test
    void enforcesResourceBudgets() {
        for (var limits : List.of(new ExecutionLimits(0, 5000, 128000000L),
                new ExecutionLimits(2001, 5000, 128000000L), new ExecutionLimits(2, 1, 128000000L),
                new ExecutionLimits(1, 5001, 128000000L), new ExecutionLimits(1, 1, 15999999L),
                new ExecutionLimits(1, 1, 128000001L))) {
            var draft = new Draft();
            draft.limits = limits;
            rejects(draft, ValidationErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void validatesBeforeStorageCapacityOrEvaluatorEvenWhenQueueIsFull() {
        var store = new InMemoryEvaluationJobStore();
        var queue = new ExecutionQueue(1, 0);
        var invoked = new AtomicBoolean();
        try (var coordinator = new AsyncSubmitCoordinator(store, queue, (request, budget) -> {
            invoked.set(true);
            throw new AssertionError("Invalid input reached evaluator");
        }); var occupied = queue.reserve(java.time.Duration.ofSeconds(10))) {
            var draft = new Draft();
            draft.suites = List.of();
            assertThatThrownBy(() -> coordinator.submit(draft.request()))
                    .isInstanceOf(RequestValidationException.class);
            assertThat(store.find(original.submissionId())).isEmpty();
            assertThat(invoked).isFalse();
        }
        boolean available = queue.execute(java.time.Duration.ofSeconds(10), budget -> true);
        assertThat(available).isTrue();
    }

    private void rejects(Draft draft, ValidationErrorCode code) {
        assertThatThrownBy(() -> validator.validate(draft.request()))
                .isInstanceOfSatisfying(RequestValidationException.class, error -> assertThat(error.code()).isEqualTo(code))
                .hasMessageNotContaining("private-source")
                .hasMessageNotContaining("HiddenTests")
                .hasMessageNotContaining("Helpers")
                .hasMessageNotContaining("Solution");
    }

    private TestSuite suite(TestVisibility visibility, List<String> paths, List<TestCaseDefinition> cases) {
        return new TestSuite("suite", visibility, paths, new JavaExecution("PublicTests"), cases);
    }

    private final class Draft {
        String id = original.submissionId();
        String version = original.taskVersionId();
        Language language = original.language();
        List<TaskFile> files = new ArrayList<>(original.taskFiles());
        List<SubmissionFile> overrides = original.submissionFiles();
        List<TestSuite> suites = new ArrayList<>(original.testSuites());
        ExecutionLimits limits = original.limits();

        SubmitRequest request() {
            return new SubmitRequest(id, version, language, files, overrides, suites, limits);
        }
    }
}
