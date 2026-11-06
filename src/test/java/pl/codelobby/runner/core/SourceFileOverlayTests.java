package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TaskFile;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceFileOverlayTests {

    private final SourceFileOverlay overlay = new SourceFileOverlay(new RunRequestValidator());
    private RunRequest original;

    @BeforeEach
    void readRequest() throws Exception {
        original = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
    }

    @Test
    void appliesContractOverrideAndPreservesPathsRolesOrderAndHostFiles() {
        var prepared = overlay.apply(original);

        assertThat(prepared).extracting(PreparedSourceFile::path)
                .containsExactlyElementsOf(original.taskFiles().stream().map(TaskFile::path).toList());
        assertThat(prepared).extracting(PreparedSourceFile::type)
                .containsExactlyElementsOf(original.taskFiles().stream().map(TaskFile::type).toList());
        assertThat(prepared.get(0).content()).isEqualTo(original.submissionFiles().getFirst().content());
        assertThat(prepared.get(1).content()).isEqualTo(original.taskFiles().get(1).content());
        assertThat(prepared.get(2).content()).isEqualTo(original.taskFiles().get(2).content());
        assertThat(original.taskFiles().getFirst().content()).contains("return 0;");
    }

    @Test
    void keepsAllStartersWhenNoParticipantChangesWereSubmitted() {
        var prepared = overlay.apply(request(original.taskFiles(), List.of()));

        assertThat(prepared).extracting(PreparedSourceFile::content)
                .containsExactlyElementsOf(original.taskFiles().stream().map(TaskFile::content).toList());
    }

    @Test
    void appliesMultipleEditsByExactPathWithoutNormalizingSource() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("src/nested/Other.java", FileType.EDITABLE, "unchanged starter"));
        String first = "package example;\r\n// żółw 😀\r\n";
        String second = "\tclass Other {}\n\n";
        var edits = List.of(new SubmissionFile("src/nested/Other.java", second),
                new SubmissionFile("src/Solution.java", first));

        var prepared = overlay.apply(request(files, edits));

        assertThat(prepared.getFirst().content()).isEqualTo(first);
        assertThat(prepared.getLast().content()).isEqualTo(second);
        assertThat(files.getLast().content()).isEqualTo("unchanged starter");
        assertThat(edits.getFirst().content()).isEqualTo(second);
    }

    @Test
    void preservesAnExplicitlyEmptyEditableFile() {
        var prepared = overlay.apply(request(original.taskFiles(),
                List.of(new SubmissionFile("src/Solution.java", ""))));

        assertThat(prepared.getFirst().content()).isEmpty();
        assertThat(prepared.getFirst().type()).isEqualTo(FileType.EDITABLE);
    }

    @ParameterizedTest
    @EnumSource(value = FileType.class, names = {"READ_ONLY", "VISIBLE_TEST", "HIDDEN_TEST"})
    void rejectsAttemptsToReplaceAnyProtectedHostRole(FileType role) {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("private/Protected.java", role, "private-host-source"));

        assertInvalid(request(files, List.of(new SubmissionFile("private/Protected.java", "replacement"))),
                ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/New.java", "SRC/Solution.java", "../private-source.java"})
    void rejectsAddedFilesCaseOnlyRenamesAndUnsafePaths(String path) {
        assertInvalid(request(original.taskFiles(), List.of(new SubmissionFile(path, "private-participant-source"))),
                ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @Test
    void rejectsDuplicateEditsRatherThanSelectingOne() {
        assertInvalid(request(original.taskFiles(), List.of(new SubmissionFile("src/Solution.java", "first"),
                new SubmissionFile("src/Solution.java", "second"))), ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @Test
    void enforcesExistingByteLimitsBeforePreparingTheResult() {
        assertInvalid(request(original.taskFiles(), List.of(
                new SubmissionFile("src/Solution.java", "ż".repeat(65537)))), ValidationErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void rejectsMissingInputThroughSafeManifestErrors() {
        assertInvalid(null, ValidationErrorCode.INVALID_SOURCE_FILES);
        assertInvalid(request(original.taskFiles(), null), ValidationErrorCode.INVALID_SOURCE_FILES);
    }

    @Test
    void returnsAnImmutableSnapshotIndependentOfMutableInputLists() {
        var files = new ArrayList<>(original.taskFiles());
        var edits = new ArrayList<>(original.submissionFiles());
        var prepared = overlay.apply(request(files, edits));

        files.clear();
        edits.clear();

        assertThat(prepared).hasSize(3);
        assertThat(prepared.getFirst().content()).isEqualTo(original.submissionFiles().getFirst().content());
        assertThatThrownBy(() -> prepared.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(overlay.apply(request(original.taskFiles(), List.of())).getFirst().content())
                .isEqualTo(original.taskFiles().getFirst().content());
    }

    @Test
    void doesNotExposePreparedSourceOrPathsInDiagnosticStrings() {
        var files = List.of(new TaskFile("private/Secret.java", FileType.EDITABLE, "private-host-source"));
        var prepared = overlay.apply(request(files,
                List.of(new SubmissionFile("private/Secret.java", "private-participant-source"))));

        assertThat(prepared.toString()).contains("<redacted>")
                .doesNotContain("private/Secret.java", "private-host-source", "private-participant-source");
    }

    private RunRequest request(List<TaskFile> files, List<SubmissionFile> edits) {
        return new RunRequest(original.requestId(), original.taskVersionId(), original.language(), files, edits,
                original.testSuites(), original.limits());
    }

    private void assertInvalid(RunRequest request, ValidationErrorCode code) {
        assertThatThrownBy(() -> overlay.apply(request))
                .isInstanceOfSatisfying(RequestValidationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.getMessage()).doesNotContain("private", "replacement", "source.java");
                });
    }
}
