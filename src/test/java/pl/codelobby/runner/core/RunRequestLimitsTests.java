package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunRequestLimitsTests {

    private final RunRequestValidator validator = new RunRequestValidator();
    private RunRequest base;

    @BeforeEach
    void readExample() throws Exception {
        base = JsonMapper.builder().build().readValue(Path.of("docs/api/examples/run-request.json").toFile(),
                RunRequest.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-request.json", "run-request-cpp.json", "run-request-python.json"})
    void acceptsCompleteContractExamples(String name) throws Exception {
        var request = JsonMapper.builder().build().readValue(Path.of("docs/api/examples", name).toFile(), RunRequest.class);
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad id", "../request", "_request", "ą"})
    void rejectsMalformedRequestAndTaskVersionIds(String id) {
        failure(new RunRequest(id, base.taskVersionId(), base.language(), base.taskFiles(), base.submissionFiles(),
                base.testSuites(), base.limits()), ValidationErrorCode.INVALID_REQUEST);
        failure(new RunRequest(base.requestId(), id, base.language(), base.taskFiles(), base.submissionFiles(),
                base.testSuites(), base.limits()), ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void checksIdentifierLengthAndMissingMetadata() {
        var ids = new RunRequest("r".repeat(128), "t".repeat(128), base.language(), base.taskFiles(),
                base.submissionFiles(), base.testSuites(), base.limits());
        assertThatCode(() -> validator.validate(ids)).doesNotThrowAnyException();
        failure(new RunRequest("r".repeat(129), ids.taskVersionId(), ids.language(), ids.taskFiles(),
                ids.submissionFiles(), ids.testSuites(), ids.limits()), ValidationErrorCode.INVALID_REQUEST);
        failure(new RunRequest(ids.requestId(), "t".repeat(129), ids.language(), ids.taskFiles(),
                ids.submissionFiles(), ids.testSuites(), ids.limits()), ValidationErrorCode.INVALID_REQUEST);
        failure(null, ValidationErrorCode.INVALID_REQUEST);
        failure(new RunRequest(null, base.taskVersionId(), base.language(), base.taskFiles(), List.of(),
                base.testSuites(), base.limits()), ValidationErrorCode.INVALID_REQUEST);
        failure(new RunRequest(base.requestId(), null, base.language(), base.taskFiles(), List.of(),
                base.testSuites(), base.limits()), ValidationErrorCode.INVALID_REQUEST);
        failure(withLimits(null), ValidationErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({"1,1,16000000", "2000,5000,128000000", "2000,2000,16000000"})
    void acceptsBudgetBoundaries(int cpu, int wall, long memory) {
        assertThatCode(() -> validator.validate(withLimits(new ExecutionLimits(cpu, wall, memory))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"0,5000,128000000", "-1,5000,128000000", "2001,5000,128000000",
            "1,0,128000000", "1,5001,128000000", "2000,1999,128000000",
            "1,1,15999999", "1,1,128000001"})
    void rejectsInvalidBudgetsWithoutClamping(int cpu, int wall, long memory) {
        var limits = new ExecutionLimits(cpu, wall, memory);
        failure(withLimits(limits), ValidationErrorCode.INVALID_REQUEST);
        assertThat(limits).isEqualTo(new ExecutionLimits(cpu, wall, memory));
    }

    @Test
    void checksFileCountAndOverrideCountBoundaries() {
        var files = IntStream.range(0, 50).mapToObj(i -> new TaskFile("src/F" + i + ".java", FileType.EDITABLE, ""))
                .toList();
        var overrides = files.stream().map(file -> new SubmissionFile(file.path(), "")).toList();
        assertThatCode(() -> validator.validateFiles(request(files, overrides, base.testSuites())))
                .doesNotThrowAnyException();
        var tooManyFiles = new ArrayList<>(files);
        tooManyFiles.add(new TaskFile("src/extra.java", FileType.EDITABLE, ""));
        failure(request(tooManyFiles, List.of(), base.testSuites()), ValidationErrorCode.PAYLOAD_TOO_LARGE);
        var tooManyOverrides = new ArrayList<>(overrides);
        tooManyOverrides.add(new SubmissionFile("src/extra.java", ""));
        failure(request(files, tooManyOverrides, base.testSuites()), ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "ą", "😀"})
    void measuresEachOriginalAndOverrideInUtf8Bytes(String character) {
        int bytes = character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String atLimit = character.repeat(131072 / bytes);
        var files = new ArrayList<>(base.taskFiles());
        files.set(0, new TaskFile(files.getFirst().path(), FileType.EDITABLE, atLimit));
        assertThatCode(() -> validator.validate(request(files, List.of(), base.testSuites())))
                .doesNotThrowAnyException();
        files.set(0, new TaskFile(files.getFirst().path(), FileType.EDITABLE, atLimit + character));
        failure(request(files, List.of(), base.testSuites()), ValidationErrorCode.PAYLOAD_TOO_LARGE);
        assertThatCode(() -> validator.validate(request(base.taskFiles(),
                List.of(new SubmissionFile("src/Solution.java", atLimit)), base.testSuites())))
                .doesNotThrowAnyException();
        failure(request(base.taskFiles(), List.of(new SubmissionFile("src/Solution.java", atLimit + character)),
                base.testSuites()), ValidationErrorCode.PAYLOAD_TOO_LARGE);
        // An oversized original is invalid even if a small override would replace it.
        failure(request(files, List.of(new SubmissionFile("src/Solution.java", "")), base.testSuites()),
                ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void checksEffectiveProjectSizeAfterOverlayWithoutDoubleCounting() {
        var files = projectFiles(9);
        failure(request(files, List.of(), base.testSuites()), ValidationErrorCode.PAYLOAD_TOO_LARGE);
        // Nine full files minus one cleared starter equals exactly 1048576 bytes.
        var reduced = request(files, List.of(new SubmissionFile("src/F0.java", "")), base.testSuites());
        assertThatCode(() -> validator.validate(reduced)).doesNotThrowAnyException();
        var eight = projectFiles(8);
        assertThatCode(() -> validator.validate(request(eight,
                List.of(new SubmissionFile("src/F0.java", "x".repeat(131072))), base.testSuites())))
                .doesNotThrowAnyException();
        eight.add(new TaskFile("src/Extra.java", FileType.EDITABLE, ""));
        failure(request(eight, List.of(new SubmissionFile("src/Extra.java", "x")), base.testSuites()),
                ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void enforcesTotalCasesAcrossSuites() {
        var first = base.testSuites().getFirst();
        var cases = IntStream.range(0, 50).mapToObj(i -> new TestCaseDefinition("case-" + i)).toList();
        var atLimit = new TestSuite(first.suiteId(), first.visibility(), first.testFilePaths(), first.execution(), cases);
        assertThatCode(() -> validator.validate(request(base.taskFiles(), List.of(), List.of(atLimit))))
                .doesNotThrowAnyException();
        var files = new ArrayList<>(base.taskFiles());
        files.add(new TaskFile("tests/Second.java", FileType.VISIBLE_TEST, ""));
        var second = new TestSuite("second", TestVisibility.PUBLIC, List.of("tests/Second.java"),
                new JavaExecution("Second"), List.of(new TestCaseDefinition("extra")));
        failure(request(files, List.of(), List.of(atLimit, second)), ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void rejectsTooManySuitesAndTestFileReferences() {
        var suite = base.testSuites().getFirst();
        failure(request(base.taskFiles(), List.of(), Collections.nCopies(51, suite)), ValidationErrorCode.PAYLOAD_TOO_LARGE);
        var excessive = new TestSuite(suite.suiteId(), suite.visibility(),
                Collections.nCopies(51, "tests/PublicTests.java"), suite.execution(), suite.cases());
        failure(request(base.taskFiles(), List.of(), List.of(excessive)), ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void keepsEmptyManifestAndEmptyCasesAsStructuralErrors() {
        failure(request(List.of(), List.of(), base.testSuites()), ValidationErrorCode.INVALID_SOURCE_FILES);
        var suite = base.testSuites().getFirst();
        failure(request(base.taskFiles(), List.of(), List.of(new TestSuite(suite.suiteId(), suite.visibility(),
                suite.testFilePaths(), suite.execution(), List.of()))), ValidationErrorCode.INVALID_REQUEST);
        failure(request(base.taskFiles(), List.of(new SubmissionFile("unknown.java", "")), base.testSuites()),
                ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    private List<TaskFile> projectFiles(int count) {
        var files = new ArrayList<TaskFile>();
        for (int i = 0; i < count; i++) {
            files.add(new TaskFile("src/F" + i + ".java", FileType.EDITABLE, "x".repeat(131072)));
        }
        files.add(new TaskFile("tests/PublicTests.java", FileType.VISIBLE_TEST, ""));
        return files;
    }

    private RunRequest withLimits(ExecutionLimits limits) {
        return new RunRequest(base.requestId(), base.taskVersionId(), base.language(), base.taskFiles(),
                base.submissionFiles(), base.testSuites(), limits);
    }

    private RunRequest request(List<TaskFile> files, List<SubmissionFile> overrides, List<TestSuite> suites) {
        return new RunRequest(base.requestId(), base.taskVersionId(), base.language(), files, overrides, suites, base.limits());
    }

    private void failure(RunRequest request, ValidationErrorCode code) {
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOfSatisfying(RequestValidationException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
