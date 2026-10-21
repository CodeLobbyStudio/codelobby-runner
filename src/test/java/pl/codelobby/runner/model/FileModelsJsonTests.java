package pl.codelobby.runner.model;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileModelsJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void readsHostFilesWithAllFourRolesFromTheContractExample() throws Exception {
        var example = mapper.readTree(Path.of("docs/api/examples/submit-request.json").toFile());
        var files = mapper.treeToValue(example.get("taskFiles"), TaskFile[].class);

        assertThat(files).extracting(TaskFile::type).containsExactly(
                FileType.EDITABLE, FileType.READ_ONLY, FileType.VISIBLE_TEST, FileType.HIDDEN_TEST);
        assertThat(files[0].path()).isEqualTo("src/Solution.java");
        assertThat(files[0].content()).isEqualTo("""
                public class Solution {
                    public static int countVowels(String text) { return 0; }
                }
                """);
    }

    @Test
    void readsParticipantFileContentsFromTheContractExample() throws Exception {
        var example = mapper.readTree(Path.of("docs/api/examples/run-request.json").toFile());
        var files = mapper.treeToValue(example.get("submissionFiles"), SubmissionFile[].class);

        assertThat(files).hasSize(1);
        assertThat(files[0].path()).isEqualTo("src/Solution.java");
        assertThat(files[0].content()).isEqualTo("""
                public class Solution {
                    public static int countVowels(String text) { return Helpers.count(text); }
                }
                """);
    }

    @Test
    void rejectsAnUnknownHostFileRole() {
        var json = """
                {"path":"src/Solution.java","type":"WRITABLE","content":""}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, TaskFile.class))
                .isInstanceOf(tools.jackson.databind.exc.InvalidFormatException.class);
    }

    @Test
    void rejectsAFileRoleInParticipantFileData() {
        var json = """
                {"path":"src/Solution.java","type":"EDITABLE","content":""}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, SubmissionFile.class))
                .isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException.class);
    }
}
