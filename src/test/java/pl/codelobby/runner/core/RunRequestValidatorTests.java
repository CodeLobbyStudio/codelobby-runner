package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TaskFile;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunRequestValidatorTests {

    private final RunRequestValidator validator = new RunRequestValidator();

    @ParameterizedTest
    @ValueSource(strings = {"run-request.json", "run-request-cpp.json", "run-request-python.json"})
    void acceptsContractFileManifests(String example) throws Exception {
        var request = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples", example).toFile(), RunRequest.class);

        assertThatCode(() -> validator.validateFiles(request)).doesNotThrowAnyException();
    }

    @Test
    void acceptsUnchangedStartersAndEmptyParticipantContents() {
        var task = List.of(editable("src/Solution.java"), editable("src/Helper.java"));

        assertThatCode(() -> validator.validateFiles(request(task, List.of()))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateFiles(request(task,
                List.of(new SubmissionFile("src/Solution.java", ""))))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/Solution.java", "C:/Solution.java", "src\\Solution.java",
            "../Solution.java", "src/./Solution.java", "src/../Solution.java", "src//Solution.java",
            "src/", "src/a b.java", "src/a\tb.java", "src/a\nb.java", "src/a\u0000b.java",
            "src/ż.java", "src/name.", "CON", "src/con.java", "src/LPT9.txt", "src/COM1",
            ".codelobby/report", "src/.CODELOBBY/report", "src/.hidden/Test.java", ".Test.java"})
    void rejectsUnsafePathsInBothManifests(String path) {
        assertInvalid(request(List.of(editable(path)), List.of()));
        assertInvalid(request(List.of(editable("src/Solution.java")), List.of(new SubmissionFile(path, ""))));
    }

    @Test
    void checksPathLengthBoundary() {
        String longest = "a".repeat(240);
        assertThatCode(() -> validator.validateFiles(request(List.of(editable(longest)), List.of())))
                .doesNotThrowAnyException();
        assertInvalid(request(List.of(editable(longest + "a")), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/Solution.java", "SRC/solution.java"})
    void rejectsDuplicateOrCaseCollidingTaskPaths(String second) {
        assertInvalid(request(List.of(editable("src/Solution.java"), editable(second)), List.of()));
    }

    @Test
    void rejectsFileDirectoryCollisionsInEitherOrder() {
        assertInvalid(request(List.of(editable("src"), editable("src/Main.java")), List.of()));
        assertInvalid(request(List.of(editable("src/Main.java"), editable("SRC")), List.of()));
        assertInvalid(request(List.of(editable("src/A.java"), editable("SRC/B.java")), List.of()));
    }

    @Test
    void acceptsSimilarNamesThatAreNotDirectoryCollisions() {
        assertThatCode(() -> validator.validateFiles(request(
                List.of(editable("src"), editable("src-other/Main.java")), List.of())))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = FileType.class, names = {"READ_ONLY", "VISIBLE_TEST", "HIDDEN_TEST"})
    void rejectsOverridesOfProtectedFiles(FileType role) {
        assertInvalid(request(List.of(editable("src/Solution.java"), new TaskFile("protected.txt", role, "secret")),
                List.of(new SubmissionFile("protected.txt", "replacement"))));
    }

    @Test
    void rejectsNewFilesAndCaseOnlyOverrideRenames() {
        var task = List.of(editable("src/Solution.java"));
        assertInvalid(request(task, List.of(new SubmissionFile("new.java", ""))));
        assertInvalid(request(task, List.of(new SubmissionFile("SRC/solution.java", ""))));
    }

    @Test
    void rejectsDuplicateOverrides() {
        assertInvalid(request(List.of(editable("src/Solution.java")), List.of(
                new SubmissionFile("src/Solution.java", "first"),
                new SubmissionFile("src/Solution.java", "second"))));
    }

    @Test
    void rejectsMissingFileDataWithoutLeakingDetails() {
        assertInvalid(null);
        assertInvalid(request(null, List.of()));
        assertInvalid(request(List.of(), List.of()));
        assertInvalid(request(List.of(editable("src/Solution.java")), null));
        assertInvalid(request(Arrays.asList((TaskFile) null), List.of()));
        assertInvalid(request(List.of(new TaskFile(null, FileType.EDITABLE, "")), List.of()));
        assertInvalid(request(List.of(new TaskFile("secret.java", null, "secret")), List.of()));
        assertInvalid(request(List.of(new TaskFile("secret.java", FileType.EDITABLE, null)), List.of()));
        assertInvalid(request(List.of(new TaskFile("secret.java", FileType.READ_ONLY, "")), List.of()));
        var task = List.of(editable("src/Solution.java"));
        assertInvalid(request(task, Arrays.asList((SubmissionFile) null)));
        assertInvalid(request(task, List.of(new SubmissionFile("src/Solution.java", null))));
    }

    private TaskFile editable(String path) {
        return new TaskFile(path, FileType.EDITABLE, "starter");
    }

    private RunRequest request(List<TaskFile> tasks, List<SubmissionFile> submissions) {
        // Other metadata is deliberately absent: this stage validates file manifests only.
        return new RunRequest(null, null, null, tasks, submissions, null, null);
    }

    private void assertInvalid(RunRequest request) {
        assertThatThrownBy(() -> validator.validateFiles(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid source file manifest");
    }
}
