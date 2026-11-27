package pl.codelobby.runner.model;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitModelsJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .build();

    @Test
    void roundTripsTheCompleteSubmitExampleIncludingHiddenSuites() {
        var original = mapper.readTree(Path.of("docs/api/examples/submit-request.json").toFile());

        var request = mapper.treeToValue(original, SubmitRequest.class);

        assertThat(request.submissionId()).isEqualTo("submission-001");
        assertThat(request.taskVersionId()).isEqualTo("task-v2");
        assertThat(request.language()).isEqualTo(Language.JAVA);
        assertThat(request.taskFiles()).extracting(TaskFile::type).containsExactly(
                FileType.EDITABLE, FileType.READ_ONLY, FileType.VISIBLE_TEST, FileType.HIDDEN_TEST);
        assertThat(request.testSuites()).extracting(TestSuite::visibility)
                .containsExactly(TestVisibility.PUBLIC, TestVisibility.HIDDEN);
        assertThat(request.testSuites().get(1).testFilePaths()).containsExactly("tests/HiddenTests.java");
        assertThat(request.testSuites().get(1).execution()).isEqualTo(new JavaExecution("HiddenTests"));
        assertThat(request.submissionFiles()).singleElement().satisfies(file ->
                assertThat(file.content()).contains("return Helpers.count(text);"));
        assertThat(request.limits()).isEqualTo(new ExecutionLimits(2000, 5000, 128000000L));
        assertThat(mapper.readTree(mapper.writeValueAsString(request))).isEqualTo(original);
    }

    @Test
    void strictDecodingRequiresTheBackendSubmissionIdInsteadOfGeneratingOne() {
        var input = mapper.readTree(Path.of("docs/api/examples/submit-request.json").toFile()).asObject();
        input.remove("submissionId");

        assertThatThrownBy(() -> mapper.treeToValue(input, SubmitRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void strictDecodingRejectsTheRunCorrelationField() {
        var input = mapper.readTree(Path.of("docs/api/examples/submit-request.json").toFile()).asObject();
        input.put("requestId", "run-only-correlation");

        assertThatThrownBy(() -> mapper.treeToValue(input, SubmitRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void keepsSourceAndHiddenMetadataOutOfItsDiagnosticRepresentation() {
        var input = mapper.readTree(Path.of("docs/api/examples/submit-request.json").toFile()).asObject();
        input.put("submissionId", "private-submission-marker");
        var request = mapper.treeToValue(input, SubmitRequest.class);

        assertThat(request.toString()).isEqualTo("SubmitRequest[<redacted>]")
                .doesNotContain("private-submission-marker", "HiddenTests", "countVowels", "aeiou");
        // Diagnostic redaction does not remove authoritative data from the internal wire payload.
        assertThat(mapper.writeValueAsString(request)).contains("private-submission-marker", "HiddenTests");
    }

    @ParameterizedTest
    @CsvSource({
            "job-queued.json, QUEUED",
            "job-running.json, RUNNING",
            "job-finished.json, FINISHED",
            "job-wrong-answer.json, FINISHED",
            "job-failed.json, FAILED"
    })
    void readsProcessingStatesFromContractExamplesWithoutConfusingThemWithVerdicts(
            String example, JobState expected) {
        var original = mapper.readTree(Path.of("docs/api/examples", example).toFile());

        var state = mapper.treeToValue(original.get("state"), JobState.class);

        assertThat(state).isEqualTo(expected);
        assertThat(mapper.readTree(mapper.writeValueAsString(state))).isEqualTo(original.get("state"));
        if (example.equals("job-wrong-answer.json")) {
            assertThat(original.get("result").get("status").asString()).isEqualTo("WRONG_ANSWER");
        }
    }
}
