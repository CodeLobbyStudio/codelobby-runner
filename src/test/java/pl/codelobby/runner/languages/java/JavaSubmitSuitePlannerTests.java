package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

class JavaSubmitSuitePlannerTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest original = mapper.readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    private final RunRequestValidator validator = new RunRequestValidator();
    private final SourceFileOverlay overlay = new SourceFileOverlay(validator);
    private final JavaSuitePlanner planner = new JavaSuitePlanner(validator, overlay);

    @Test
    void preparesPublicAndHiddenPlansWithAuthoritativeTestsAndParticipantEdits() {
        var plans = planner.prepareSubmit(original);

        assertThat(plans).extracting(JavaSuitePlan::suiteId).containsExactly("public-suite", "hidden-suite");
        assertThat(plans.getFirst().mainClass()).isEqualTo("PublicTests");
        assertThat(plans.getLast().mainClass()).isEqualTo("HiddenTests");
        assertThat(plans.getFirst().testIds()).containsExactly("public-1");
        assertThat(plans.getLast().testIds()).containsExactly("hidden-1");
        assertThat(plans.getFirst().files()).extracting(PreparedSourceFile::path)
                .containsExactly("src/Solution.java", "src/Helpers.java", "tests/PublicTests.java");
        assertThat(plans.getLast().files()).extracting(PreparedSourceFile::path)
                .containsExactly("src/Solution.java", "src/Helpers.java", "tests/HiddenTests.java");
        for (var plan : plans) {
            assertThat(plan.files().getFirst().content()).isEqualTo(original.submissionFiles().getFirst().content());
            assertThat(plan.files().get(1).content()).isEqualTo(original.taskFiles().get(1).content());
            var test = plan.files().getLast();
            assertThat(test.content()).isEqualTo(original.taskFiles().stream()
                    .filter(file -> file.path().equals(test.path())).findFirst().orElseThrow().content());
        }
        assertThat(original.taskFiles().getFirst().content()).contains("return 0;");
    }

    @Test
    void keepsOtherPublicAndHiddenSuitesOutOfEachPlanAndPreservesCaseOrder() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("tests/edge/EdgeTests.java", FileType.VISIBLE_TEST, "public-private-source"));
        files.add(new TaskFile("tests/secret/SecretTests.java", FileType.HIDDEN_TEST, "hidden-private-source"));
        files.add(new TaskFile("tests/secret/Support.java", FileType.HIDDEN_TEST, "hidden-private-support"));
        var edge = suite("edge", TestVisibility.PUBLIC, List.of("tests/edge/EdgeTests.java"),
                "edge.EdgeTests", List.of(new TestCaseDefinition("edge-2"), new TestCaseDefinition("edge-1")));
        var secret = suite("secret", TestVisibility.HIDDEN,
                List.of("tests/secret/Support.java", "tests/secret/SecretTests.java"), "secret.SecretTests",
                List.of(new TestCaseDefinition("secret-2"), new TestCaseDefinition("secret-1")));
        var suites = List.of(original.testSuites().getFirst(), edge, original.testSuites().getLast(), secret);

        var plans = planner.prepareSubmit(request(files, List.of(), suites));

        assertThat(plans).extracting(JavaSuitePlan::suiteId)
                .containsExactly("public-suite", "edge", "hidden-suite", "secret");
        assertThat(plans.get(1).testIds()).containsExactly("edge-2", "edge-1");
        assertThat(plans.getLast().testIds()).containsExactly("secret-2", "secret-1");
        for (int i = 0; i < plans.size(); i++) {
            assertThat(plans.get(i).files().stream()
                    .filter(file -> file.type() == FileType.VISIBLE_TEST || file.type() == FileType.HIDDEN_TEST)
                    .map(PreparedSourceFile::path)).containsExactlyInAnyOrderElementsOf(suites.get(i).testFilePaths());
        }
        assertThat(plans.getLast().files()).extracting(PreparedSourceFile::path).containsExactly(
                "src/Solution.java", "src/Helpers.java", "tests/secret/SecretTests.java", "tests/secret/Support.java");
    }

    @Test
    void supportsMultipleEditableFilesAndPreservesEmptyOverridesAndUnicode() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("src/nested/Other.java", FileType.EDITABLE, "starter"));
        var edits = List.of(new SubmissionFile("src/Solution.java", ""),
                new SubmissionFile("src/nested/Other.java", "package nested;\r\n// żółw 😀\r\n"));

        var plans = planner.prepareSubmit(request(files, edits, original.testSuites()));

        for (var plan : plans) {
            assertThat(plan.files().getFirst().content()).isEmpty();
            assertThat(plan.files().getLast().content()).isEqualTo(edits.getLast().content());
        }
    }

    @Test
    void preservesUnchangedStartersWhenThereAreNoOverrides() {
        var plans = planner.prepareSubmit(request(original.taskFiles(), List.of(), original.testSuites()));
        assertThat(plans.getFirst().files().getFirst().content()).isEqualTo(original.taskFiles().getFirst().content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/Helpers.java", "tests/PublicTests.java", "tests/HiddenTests.java", "unknown.java"})
    void rejectsProtectedAndUndeclaredOverridesBeforePlanning(String path) {
        var input = request(original.taskFiles(), List.of(new SubmissionFile(path, "private-edit")), original.testSuites());
        assertInvalid(input, ValidationErrorCode.INVALID_SOURCE_FILES);
        assertThatThrownBy(() -> overlay.applySubmit(input)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void validatesMetadataLimitsAndAllSuiteLinksBeforeReturningAnyPlan() {
        assertInvalid(null, ValidationErrorCode.INVALID_REQUEST);
        assertInvalid(new SubmitRequest("bad id", original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), original.limits()), ValidationErrorCode.INVALID_REQUEST);
        assertInvalid(new SubmitRequest(original.submissionId(), original.taskVersionId(), original.language(),
                original.taskFiles(), original.submissionFiles(), original.testSuites(),
                new ExecutionLimits(2001, 5000, 128000000)), ValidationErrorCode.INVALID_REQUEST);
        var missing = suite("hidden", TestVisibility.HIDDEN, List.of("tests/Missing.java"), "Missing",
                List.of(new TestCaseDefinition("hidden-1")));
        assertInvalid(request(original.taskFiles(), List.of(), List.of(original.testSuites().getFirst(), missing)),
                ValidationErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-request-cpp.json", "run-request-python.json"})
    void rejectsOtherLanguagesInTheJavaPlanner(String example) throws Exception {
        var run = mapper.readValue(Path.of("docs/api/examples", example).toFile(), RunRequest.class);
        assertInvalid(new SubmitRequest("job", run.taskVersionId(), run.language(), run.taskFiles(),
                run.submissionFiles(), run.testSuites(), run.limits()), ValidationErrorCode.INVALID_REQUEST);
    }

    @Test
    void returnsImmutableSnapshotsIndependentOfMutableInputLists() {
        var files = new ArrayList<>(original.taskFiles());
        var edits = new ArrayList<>(original.submissionFiles());
        var paths = new ArrayList<>(original.testSuites().getLast().testFilePaths());
        var cases = new ArrayList<>(original.testSuites().getLast().cases());
        var hidden = suite("hidden", TestVisibility.HIDDEN, paths, "HiddenTests", cases);
        var suites = new ArrayList<>(List.of(original.testSuites().getFirst(), hidden));
        var input = request(files, edits, suites);
        var overlaid = overlay.applySubmit(input);
        var plans = planner.prepareSubmit(input);
        files.clear();
        edits.clear();
        paths.clear();
        cases.clear();
        suites.clear();

        assertThat(overlaid).hasSize(4);
        assertThat(plans).hasSize(2);
        assertThat(plans.getLast().files()).hasSize(3);
        assertThat(plans.getLast().testIds()).containsExactly("hidden-1");
        assertThatThrownBy(() -> overlaid.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plans.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plans.getLast().files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plans.getLast().testIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void publicOnlySubmitUsesTheSameSourcesAsRunAndDiagnosticsRedactSources() throws Exception {
        var run = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
        var input = new SubmitRequest("job", run.taskVersionId(), run.language(), run.taskFiles(),
                run.submissionFiles(), run.testSuites(), run.limits());
        assertThat(planner.prepareSubmit(input)).isEqualTo(planner.prepare(run));
        assertThat(planner.prepareSubmit(original).toString()).contains("<redacted>")
                .doesNotContain("tests/HiddenTests.java", "mainClass=HiddenTests", "return 0;", "countVowels");
    }

    @Test
    void planningDoesNotEnableHiddenArchivesInTheExistingRunBuilder() {
        var plans = planner.prepareSubmit(original);
        var archive = new JavaSuiteArchive();
        assertThatCode(() -> archive.create(plans.getFirst())).doesNotThrowAnyException();
        assertThatThrownBy(() -> archive.create(plans.getLast())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("HiddenTests");
    }

    private TestSuite suite(String id, TestVisibility visibility, List<String> paths,
            String mainClass, List<TestCaseDefinition> cases) {
        return new TestSuite(id, visibility, paths, new JavaExecution(mainClass), cases);
    }

    private SubmitRequest request(List<TaskFile> files, List<SubmissionFile> edits, List<TestSuite> suites) {
        return new SubmitRequest(original.submissionId(), original.taskVersionId(), original.language(),
                files, edits, suites, original.limits());
    }

    private void assertInvalid(SubmitRequest input, ValidationErrorCode code) {
        assertThatThrownBy(() -> planner.prepareSubmit(input))
                .isInstanceOfSatisfying(RequestValidationException.class, error -> assertThat(error.code()).isEqualTo(code))
                .hasMessageNotContaining("private-edit")
                .hasMessageNotContaining("HiddenTests");
    }
}
