package pl.codelobby.runner.model;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestResultJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @ParameterizedTest
    @CsvSource({
            "run-accepted.json, ACCEPTED, ACCEPTED",
            "run-wrong-answer.json, WRONG_ANSWER, WRONG_ANSWER",
            "run-compilation-error.json, COMPILATION_ERROR, NOT_RUN"
    })
    void readsOverallAndCaseVerdictsFromContractExamples(
            String file, Verdict overall, TestVerdict expectedCaseStatus) throws Exception {
        var result = mapper.readTree(Path.of("docs/api/examples", file).toFile()).get("result");
        var testJson = result.get("testResults").get(0);
        var test = mapper.treeToValue(testJson, TestResult.class);

        assertThat(mapper.treeToValue(result.get("status"), Verdict.class)).isEqualTo(overall);
        assertThat(test.status()).isEqualTo(expectedCaseStatus);
        assertThat(test.testId()).isEqualTo("public-1");
        assertThat(test.suiteId()).isEqualTo("public-suite");
        assertThat(test.visibility()).isEqualTo(TestVisibility.PUBLIC);
        assertThat(mapper.readTree(mapper.writeValueAsString(test))).isEqualTo(testJson);
    }

    @Test
    void readsAndWritesHiddenResultsWithNullDiagnostics() throws Exception {
        var example = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile());
        var json = example.get("result").get("testResults").get(1);
        var result = mapper.treeToValue(json, TestResult.class);

        assertThat(result.visibility()).isEqualTo(TestVisibility.HIDDEN);
        assertThat(result.status()).isEqualTo(TestVerdict.ACCEPTED);
        assertThat(result.input()).isNull();
        assertThat(result.expectedOutput()).isNull();
        assertThat(result.actualOutput()).isNull();
        assertThat(result.stdout()).isNull();
        assertThat(result.stderr()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(result))).isEqualTo(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"text\":\"example\"}", "[1,2]", "\"answer\"", "3", "true", "null"})
    void preservesDifferentPublicDiagnosticJsonTypes(String value) throws Exception {
        var json = mapper.readTree("""
                {"testId":"case-1","suiteId":"suite-1","visibility":"PUBLIC",
                 "status":"ACCEPTED","input":null,"expectedOutput":null,
                 "actualOutput":null,"stdout":"","stderr":null}
                """).asObject();
        json.set("actualOutput", mapper.readTree(value));

        var result = mapper.treeToValue(json, TestResult.class);

        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(result))).isEqualTo(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "expectedOutput", "actualOutput", "stdout", "stderr"})
    void rejectsDiagnosticDataInHiddenResults(String field) throws Exception {
        var example = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile());
        var hidden = example.get("result").get("testResults").get(1).deepCopy().asObject();
        hidden.put(field, "sensitive test data");

        assertThatThrownBy(() -> mapper.treeToValue(hidden, TestResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotAcceptCompilationErrorAsAnIndividualCaseStatus() {
        assertThatThrownBy(() -> mapper.readValue("\"COMPILATION_ERROR\"", TestVerdict.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void rejectsAnUnknownOverallVerdict() {
        assertThatThrownBy(() -> mapper.readValue("\"PASSED\"", Verdict.class))
                .isInstanceOf(InvalidFormatException.class);
    }
}
