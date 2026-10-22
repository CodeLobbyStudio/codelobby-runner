package pl.codelobby.runner.model;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @ParameterizedTest
    @CsvSource({
            "run-accepted.json, NOT_REQUESTED",
            "job-wrong-answer.json, SKIPPED",
            "job-finished.json, SUCCEEDED",
            "job-benchmark-failed.json, FAILED"
    })
    void preservesAllBenchmarkOutcomesFromContractExamples(String file, BenchmarkState state) throws Exception {
        var json = mapper.readTree(Path.of("docs/api/examples", file).toFile()).get("result").get("benchmark");
        var benchmark = mapper.treeToValue(json, Benchmark.class);

        assertThat(benchmark.state()).isEqualTo(state);
        assertThat(benchmark.measurementScope()).isEqualTo(MeasurementScope.ALL_TEST_SUITES);
        assertThat(benchmark.measurementTrust()).isEqualTo(MeasurementTrust.SANDBOX_EXTERNAL);
        if (state == BenchmarkState.SUCCEEDED) {
            assertThat(benchmark.reason()).isNull();
            assertThat(benchmark.metrics().roundRuntimeMs()).hasSize(5);
            assertThat(benchmark.metrics().roundSandboxCpuTimeMs()).hasSize(5);
            assertThat(benchmark.metrics().roundPeakMemoryBytes()).hasSize(5);
        } else {
            assertThat(benchmark.reason()).isNotNull();
            assertThat(benchmark.metrics()).isNull();
        }
        assertThat(mapper.readTree(mapper.writeValueAsString(benchmark))).isEqualTo(json);
    }

    @Test
    void rejectsSuccessfulBenchmarkWithoutMeasurements() throws Exception {
        var json = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile())
                .get("result").get("benchmark").deepCopy().asObject();
        json.putNull("metrics");

        assertThatThrownBy(() -> mapper.treeToValue(json, Benchmark.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFailureReasonForSuccessfulBenchmark() throws Exception {
        var json = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile())
                .get("result").get("benchmark").deepCopy().asObject();
        json.put("reason", "INFRASTRUCTURE_FAILURE");

        assertThatThrownBy(() -> mapper.treeToValue(json, Benchmark.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeMeasurements() throws Exception {
        var json = mapper.readTree(Path.of("docs/api/examples/job-finished.json").toFile())
                .get("result").get("benchmark").get("metrics").deepCopy().asObject();
        json.put("runtimeMs", -1);

        assertThatThrownBy(() -> mapper.treeToValue(json, BenchmarkMetrics.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
