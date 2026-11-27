package pl.codelobby.runner.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationJobJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @ParameterizedTest
    @CsvSource({
            "job-queued.json, QUEUED",
            "job-running.json, RUNNING",
            "job-finished.json, FINISHED",
            "job-wrong-answer.json, FINISHED",
            "job-benchmark-failed.json, FINISHED",
            "job-failed.json, FAILED"
    })
    void roundTripsCompleteJobSnapshotsWithUtcTimestamps(String file, JobState expected) {
        var original = mapper.readTree(example(file).toFile());

        var job = mapper.treeToValue(original, EvaluationJob.class);

        assertThat(job.state()).isEqualTo(expected);
        assertThat(job.createdAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(mapper.readTree(mapper.writeValueAsString(job))).isEqualTo(original);
    }

    @Test
    void distinguishesNegativeSolutionAndBenchmarkFailureFromProcessingFailure() {
        var wrong = mapper.readValue(example("job-wrong-answer.json").toFile(), EvaluationJob.class);
        var benchmarkFailed = mapper.readValue(example("job-benchmark-failed.json").toFile(), EvaluationJob.class);

        assertThat(wrong.state()).isEqualTo(JobState.FINISHED);
        assertThat(wrong.result().status()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(wrong.error()).isNull();
        assertThat(benchmarkFailed.state()).isEqualTo(JobState.FINISHED);
        assertThat(benchmarkFailed.result().status()).isEqualTo(Verdict.ACCEPTED);
        assertThat(benchmarkFailed.result().benchmark().state()).isEqualTo(BenchmarkState.FAILED);
        assertThat(benchmarkFailed.error()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"job-queued.json", "job-running.json"})
    void rejectsPartialResultsAndErrorsInActiveJobs(String file) {
        var active = mapper.readTree(example(file).toFile()).asObject();
        var finished = mapper.readTree(example("job-finished.json").toFile());
        active.set("result", finished.get("result"));
        assertThatThrownBy(() -> mapper.treeToValue(active, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        active.putNull("result");
        active.set("error", mapper.readTree(example("job-failed.json").toFile()).get("error"));
        assertThatThrownBy(() -> mapper.treeToValue(active, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"QUEUED, startedAt", "QUEUED, finishedAt", "RUNNING, expiresAt"})
    void rejectsPrematureLifecycleTimestamps(JobState state, String field) {
        var input = mapper.readTree(example(state == JobState.QUEUED ? "job-queued.json" : "job-running.json")
                .toFile()).asObject();
        input.put(field, "2026-10-20T10:00:05Z");
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "job-running.json, startedAt",
            "job-finished.json, startedAt",
            "job-finished.json, finishedAt",
            "job-finished.json, expiresAt",
            "job-finished.json, result",
            "job-failed.json, finishedAt",
            "job-failed.json, expiresAt",
            "job-failed.json, error"
    })
    void rejectsMissingStateSpecificFields(String file, String field) {
        var input = mapper.readTree(example(file).toFile()).asObject();
        input.putNull(field);
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsProcessingFailureBeforeExecutionStarts() {
        var input = mapper.readTree(example("job-failed.json").toFile()).asObject();
        input.putNull("startedAt");
        var job = mapper.treeToValue(input, EvaluationJob.class);
        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.startedAt()).isNull();
        assertThat(job.result()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "startedAt, 2026-10-20T09:59:59Z",
            "finishedAt, 2026-10-20T10:00:00Z",
            "expiresAt, 2026-10-20T10:29:04Z",
            "expiresAt, 2026-10-20T10:31:04Z"
    })
    void rejectsOutOfOrderTimestampsAndIncorrectRetention(String field, String value) {
        var input = mapper.readTree(example("job-finished.json").toFile()).asObject();
        input.put(field, value);
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "expectedOutput", "actualOutput", "stdout", "stderr"})
    void rejectsHiddenDiagnosticValues(String field) {
        var input = mapper.readTree(example("job-finished.json").toFile()).asObject();
        input.get("result").get("testResults").get(1).asObject().put(field, "private-marker");
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCompilerDiagnosticsWhenHiddenTestsArePresent() {
        var input = mapper.readTree(example("job-finished.json").toFile()).asObject();
        input.get("result").asObject().put("compileOutput", "private-marker");
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesSummaryAndBenchmarkPolicyWithoutPerformingMeasurements() {
        var input = mapper.readTree(example("job-finished.json").toFile()).get("result").asObject();
        input.put("passedTests", 1);
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        input.put("passedTests", 2);
        input.set("benchmark", mapper.readTree(example("run-accepted.json").toFile()).get("result").get("benchmark"));
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsAnImmutableListOfCaseResults() {
        var original = mapper.readValue(example("job-finished.json").toFile(), EvaluationJob.class).result();
        var cases = new ArrayList<>(original.testResults());
        var result = new EvaluationResult(original.status(), original.passedTests(), original.totalTests(), cases,
                original.compileOutput(), original.message(), original.sandboxCpuTimeMs(),
                original.sandboxPeakMemoryBytes(), original.benchmark(), original.gradingIntegrity());
        cases.clear();
        assertThat(result.testResults()).hasSize(2);
        assertThatThrownBy(() -> result.testResults().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void redactsDiagnosticStringRepresentationsWithoutChangingTheWirePayload() {
        var job = mapper.readValue(example("job-failed.json").toFile(), EvaluationJob.class);
        assertThat(job.toString()).isEqualTo("EvaluationJob[<redacted>]");
        assertThat(job.error().toString()).isEqualTo("JobError[<redacted>]");
        assertThat(mapper.writeValueAsString(job)).contains("EXECUTION_INFRASTRUCTURE_ERROR");
        var finished = mapper.readValue(example("job-finished.json").toFile(), EvaluationJob.class);
        assertThat(finished.result().toString()).isEqualTo("EvaluationResult[<redacted>]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"job-finished.json", "job-failed.json"})
    void rejectsResultAndErrorTogetherInTerminalJobs(String file) {
        var input = mapper.readTree(example(file).toFile()).asObject();
        input.set("result", mapper.readTree(example("job-finished.json").toFile()).get("result"));
        input.set("error", mapper.readTree(example("job-failed.json").toFile()).get("error"));
        assertThatThrownBy(() -> mapper.treeToValue(input, EvaluationJob.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsErrorsOutsideTheContractWithoutEchoingTheirValues() {
        assertThatThrownBy(() -> new JobError("private-marker", "private-marker", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid job error payload");
        assertThatThrownBy(() -> new JobError("INTERNAL_SERVER_ERROR", " ", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobError("INTERNAL_SERVER_ERROR", "x".repeat(1025), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "NaN", "Infinity"})
    void rejectsNonfiniteOrNegativeCpuMeasurements(String invalid) {
        var original = mapper.readValue(example("job-finished.json").toFile(), EvaluationJob.class).result();
        assertThatThrownBy(() -> new EvaluationResult(original.status(), original.passedTests(),
                original.totalTests(), original.testResults(), original.compileOutput(), original.message(),
                Double.parseDouble(invalid), original.sandboxPeakMemoryBytes(), original.benchmark(),
                original.gradingIntegrity())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeMemoryMeasurements() {
        var original = mapper.readValue(example("job-finished.json").toFile(), EvaluationJob.class).result();
        assertThatThrownBy(() -> new EvaluationResult(original.status(), original.passedTests(),
                original.totalTests(), original.testResults(), original.compileOutput(), original.message(),
                original.sandboxCpuTimeMs(), -1L, original.benchmark(), original.gradingIntegrity()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path example(String name) {
        return Path.of("docs/api/examples", name);
    }
}
