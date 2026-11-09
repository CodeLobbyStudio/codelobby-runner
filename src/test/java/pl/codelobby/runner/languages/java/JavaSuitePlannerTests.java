package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.PreparedSourceFile;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.PythonExecution;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSuitePlannerTests {

    private final RunRequestValidator validator = new RunRequestValidator();
    private final JavaSuitePlanner planner = new JavaSuitePlanner(validator, new SourceFileOverlay(validator));
    private RunRequest original;

    @BeforeEach
    void readRequest() throws Exception {
        original = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
    }

    @Test
    void preparesTheContractSuiteWithParticipantEditsAndHostEntryPoint() {
        var plans = planner.prepare(original);

        assertThat(plans).hasSize(1);
        var plan = plans.getFirst();
        assertThat(plan.suiteId()).isEqualTo("public-suite");
        assertThat(plan.mainClass()).isEqualTo("PublicTests");
        assertThat(plan.testIds()).containsExactly("public-1");
        assertThat(plan.files()).extracting(PreparedSourceFile::path)
                .containsExactly("src/Solution.java", "src/Helpers.java", "tests/PublicTests.java");
        assertThat(plan.files().getFirst().content()).isEqualTo(original.submissionFiles().getFirst().content());
        assertThat(plan.files().getLast().content()).isEqualTo(original.taskFiles().getLast().content());
        assertThat(original.taskFiles().getFirst().content()).contains("return 0;");
    }

    @Test
    void includesSharedSourcesButOnlyEachSuitesOwnTests() {
        var files = twoSuiteFiles();
        files.add(new TaskFile("src/nested/Other.java", FileType.EDITABLE, "second starter"));
        var edits = new ArrayList<>(original.submissionFiles());
        edits.add(new SubmissionFile("src/nested/Other.java", "package nested;\r\n// żółw 😀\r\n"));

        var plans = planner.prepare(request(files, edits, twoSuites()));

        assertThat(plans).extracting(JavaSuitePlan::suiteId).containsExactly("public-suite", "edge-suite");
        assertThat(plans.get(0).files()).extracting(PreparedSourceFile::path)
                .containsExactly("src/Solution.java", "src/Helpers.java", "tests/PublicTests.java", "src/nested/Other.java");
        assertThat(plans.get(1).files()).extracting(PreparedSourceFile::path)
                .containsExactly("src/Solution.java", "src/Helpers.java", "tests/edge/EdgeTests.java", "src/nested/Other.java");
        assertThat(plans.get(1).mainClass()).isEqualTo("edge.EdgeTests");
        for (var plan : plans) {
            assertThat(plan.files().getLast().type()).isEqualTo(FileType.EDITABLE);
            assertThat(plan.files().getLast().content()).isEqualTo(edits.getLast().content());
            assertThat(plan.files().get(1).content()).isEqualTo(original.taskFiles().get(1).content());
        }
    }

    @Test
    void preservesSuiteAndCaseOrderAndIncludesAllDeclaredTestFiles() {
        var files = twoSuiteFiles();
        files.add(new TaskFile("tests/edge/TestHelper.java", FileType.VISIBLE_TEST, "package edge; class TestHelper {}"));
        var edge = new TestSuite("edge-suite", TestVisibility.PUBLIC,
                List.of("tests/edge/TestHelper.java", "tests/edge/EdgeTests.java"), new JavaExecution("edge.EdgeTests"),
                List.of(new TestCaseDefinition("edge-2"), new TestCaseDefinition("edge-1")));

        var plans = planner.prepare(request(files, List.of(), List.of(edge, original.testSuites().getFirst())));

        assertThat(plans).extracting(JavaSuitePlan::suiteId).containsExactly("edge-suite", "public-suite");
        assertThat(plans.getFirst().testIds()).containsExactly("edge-2", "edge-1");
        assertThat(plans.getFirst().files()).extracting(PreparedSourceFile::path)
                .containsExactly("src/Solution.java", "src/Helpers.java", "tests/edge/EdgeTests.java", "tests/edge/TestHelper.java");
        assertThat(plans.getFirst().files().getFirst().content()).isEqualTo(original.taskFiles().getFirst().content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tests/Missing.java", "src/Solution.java", "src/Helpers.java"})
    void rejectsMissingOrNonTestReferences(String path) {
        var invalid = suite("bad-suite", path, "BadTests", "case-1");
        assertInvalid(request(original.taskFiles(), List.of(), List.of(invalid)), ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsOrphanedTestsAndFilesAssignedToTwoSuites() {
        assertInvalid(request(twoSuiteFiles(), List.of(), original.testSuites()), ValidationErrorCode.INVALID_REQUEST);
        var duplicate = suite("second-suite", "tests/PublicTests.java", "PublicTests", "second-case");
        assertInvalid(request(original.taskFiles(), List.of(), List.of(original.testSuites().getFirst(), duplicate)),
                ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsDuplicateCaseIdsAcrossSuites() {
        var duplicate = suite("edge-suite", "tests/edge/EdgeTests.java", "edge.EdgeTests", "public-1");
        assertInvalid(request(twoSuiteFiles(), List.of(), List.of(original.testSuites().getFirst(), duplicate)),
                ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsHiddenFilesAndHiddenSuitesForRun() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("tests/Hidden.java", FileType.HIDDEN_TEST, "private-test-source"));
        assertInvalid(request(files, List.of(), original.testSuites()), ValidationErrorCode.INVALID_REQUEST);
        var publicSuite = original.testSuites().getFirst();
        var hidden = new TestSuite(publicSuite.suiteId(), TestVisibility.HIDDEN, publicSuite.testFilePaths(),
                publicSuite.execution(), publicSuite.cases());
        assertInvalid(request(original.taskFiles(), List.of(), List.of(hidden)), ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsAttemptsToOverrideHostTests() {
        assertInvalid(request(original.taskFiles(), List.of(new SubmissionFile("tests/PublicTests.java", "replacement")),
                original.testSuites()), ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-request-cpp.json", "run-request-python.json"})
    void rejectsOtherLanguagesRatherThanPreparingJavaPlans(String example) throws Exception {
        var request = JsonMapper.builder().build().readValue(Path.of("docs/api/examples", example).toFile(), RunRequest.class);
        assertInvalid(request, ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void performsFullMetadataAndBudgetValidationBeforePlanning() {
        assertInvalid(null, ValidationErrorCode.INVALID_REQUEST);
        assertInvalid(new RunRequest("bad id", original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), original.limits()), ValidationErrorCode.INVALID_REQUEST);
        assertInvalid(new RunRequest(original.requestId(), original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), new ExecutionLimits(2001, 5000, 128000000)),
                ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsNonJavaOrMalformedEntryMetadata() {
        var originalSuite = original.testSuites().getFirst();
        var wrongProfile = new TestSuite(originalSuite.suiteId(), originalSuite.visibility(), originalSuite.testFilePaths(),
                new PythonExecution("tests/PublicTests.java"), originalSuite.cases());
        assertInvalid(request(original.taskFiles(), List.of(), List.of(wrongProfile)), ValidationErrorCode.INVALID_REQUEST);
        assertInvalid(request(original.taskFiles(), List.of(), List.of(
                suite("public-suite", "tests/PublicTests.java", "private.Bad;command", "public-1"))),
                ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void plansAreImmutableSnapshotsOfMutableRequestLists() {
        var files = twoSuiteFiles();
        var cases = new ArrayList<>(List.of(new TestCaseDefinition("edge-1")));
        var paths = new ArrayList<>(List.of("tests/edge/EdgeTests.java"));
        var suites = new ArrayList<>(List.of(original.testSuites().getFirst(),
                new TestSuite("edge-suite", TestVisibility.PUBLIC, paths, new JavaExecution("edge.EdgeTests"), cases)));
        var edits = new ArrayList<>(original.submissionFiles());

        var plans = planner.prepare(request(files, edits, suites));
        files.clear();
        suites.clear();
        edits.clear();
        paths.clear();
        cases.clear();

        assertThat(plans).hasSize(2);
        assertThat(plans.getLast().files()).hasSize(3);
        assertThat(plans.getLast().testIds()).containsExactly("edge-1");
        assertThatThrownBy(() -> plans.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plans.getLast().files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plans.getLast().testIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(plans.getFirst().files()).isNotSameAs(plans.getLast().files());
    }

    @Test
    void diagnosticStringsDoNotExposeTestCodePathsOrEntryClass() {
        var plans = planner.prepare(request(twoSuiteFiles(), List.of(), twoSuites()));
        assertThat(plans.toString()).contains("<redacted>")
                .doesNotContain("edge.EdgeTests", "tests/edge/EdgeTests.java", "private-test-source", "return 0;");
    }

    private ArrayList<TaskFile> twoSuiteFiles() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("tests/edge/EdgeTests.java", FileType.VISIBLE_TEST, "private-test-source"));
        return files;
    }

    private List<TestSuite> twoSuites() {
        return List.of(original.testSuites().getFirst(),
                suite("edge-suite", "tests/edge/EdgeTests.java", "edge.EdgeTests", "edge-1"));
    }

    private TestSuite suite(String id, String path, String mainClass, String testId) {
        return new TestSuite(id, TestVisibility.PUBLIC, List.of(path), new JavaExecution(mainClass),
                List.of(new TestCaseDefinition(testId)));
    }

    private RunRequest request(List<TaskFile> files, List<SubmissionFile> edits, List<TestSuite> suites) {
        return new RunRequest(original.requestId(), original.taskVersionId(), original.language(), files, edits,
                suites, original.limits());
    }

    private void assertInvalid(RunRequest request, ValidationErrorCode code) {
        assertThatThrownBy(() -> planner.prepare(request))
                .isInstanceOfSatisfying(RequestValidationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.getMessage()).doesNotContain("private", "replacement", "Bad;command");
                });
    }
}
