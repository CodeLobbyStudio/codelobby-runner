package pl.codelobby.runner.model;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunResponseJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @ParameterizedTest
    @CsvSource({
            "run-accepted.json, ACCEPTED, 1",
            "run-wrong-answer.json, WRONG_ANSWER, 0",
            "run-compilation-error.json, COMPILATION_ERROR, 0"
    })
    void readsAndWritesCompleteContractResponses(String file, Verdict status, int passed) throws Exception {
        var json = mapper.readTree(Path.of("docs/api/examples", file).toFile());
        var response = mapper.treeToValue(json, RunResponse.class);

        assertThat(response.requestId()).isNotBlank();
        assertThat(response.taskVersionId()).isEqualTo("task-v2");
        assertThat(response.contractVersion()).isEqualTo("1.0.0-draft.2");
        assertThat(response.result().status()).isEqualTo(status);
        assertThat(response.result().passedTests()).isEqualTo(passed);
        assertThat(response.result().totalTests()).isEqualTo(1);
        assertThat(response.result().benchmark().state()).isEqualTo(BenchmarkState.NOT_REQUESTED);
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(json);
    }

    @Test
    void preservesUnavailableMeasurementsAsNull() throws Exception {
        var response = mapper.readValue(Path.of("docs/api/examples/run-compilation-error.json").toFile(),
                RunResponse.class);

        assertThat(response.result().sandboxCpuTimeMs()).isNull();
        assertThat(response.result().sandboxPeakMemoryBytes()).isNull();
        assertThat(response.result().benchmark().metrics()).isNull();
        assertThat(response.result().compileOutput()).isNotBlank();
    }

    @Test
    void rejectsHiddenCasesEvenWhenTheirDiagnosticsAreRedacted() throws Exception {
        var result = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile()).get("result");

        assertThatThrownBy(() -> mapper.treeToValue(result, RunResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Run results must contain public tests only");
    }

    @Test
    void rejectsSummaryThatDoesNotMatchTheCases() throws Exception {
        var result = mapper.readTree(Path.of("docs/api/examples/run-wrong-answer.json").toFile())
                .get("result").deepCopy().asObject();
        result.put("passedTests", 1);

        assertThatThrownBy(() -> mapper.treeToValue(result, RunResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBenchmarkExecutionInRun() throws Exception {
        var result = mapper.readTree(Path.of("docs/api/examples/run-accepted.json").toFile())
                .get("result").deepCopy().asObject();
        var benchmark = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile())
                .get("result").get("benchmark");
        result.set("benchmark", benchmark);

        assertThatThrownBy(() -> mapper.treeToValue(result, RunResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Run does not execute benchmarks");
    }
}
