package pl.codelobby.runner.languages.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0ArtifactStore;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactException;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactSnapshot;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactStore;
import pl.codelobby.runner.sandbox.judge0.Judge0Properties;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_COMPILED_SESSION", matches = "true")
class Judge0CompiledJavaSubmissionIntegrationTests {

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "wrong-answer", "unavailable-artifacts"})
    void evaluatesAndBenchmarksThroughPrivateHttpWithoutDockerCli(String scenario) throws Exception {
        var original = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
        var request = scenario.equals("wrong-answer") ? new SubmitRequest(original.submissionId(), original.taskVersionId(),
                original.language(), original.taskFiles(), List.of(new SubmissionFile("src/Solution.java",
                "public class Solution { public static int countVowels(String text) { return 0; } }")),
                original.testSuites(), original.limits()) : original;
        var properties = new Judge0Properties(URI.create("http://127.0.0.1:2359"), 1001,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(200), Duration.ofSeconds(60));
        String key = System.getenv("CODELOBBY_ARTIFACT_KEY");
        var fetched = new ArrayList<Judge0SubmissionToken>();
        var deletions = new ArrayList<Judge0SubmissionToken>();
        try (var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var engine = new HttpJudge0Client(http, properties);
            var authorizedStore = new HttpJudge0ArtifactStore(http, properties, key);
            var usedStore = scenario.equals("unavailable-artifacts")
                    ? new HttpJudge0ArtifactStore(http, properties, "wrong-key-" + "x".repeat(40)) : authorizedStore;
            var recording = new Judge0ArtifactStore() {
                @Override
                public Judge0ArtifactSnapshot fetch(Judge0SubmissionToken token, Duration left) {
                    fetched.add(token);
                    return usedStore.fetch(token, left);
                }
                @Override
                public void awaitCleanup(Judge0SubmissionToken token, Duration left) {
                    // Fault injection affects artifact retrieval only. Cleanup must remain verifiable.
                    authorizedStore.awaitCleanup(token, left);
                }
                @Override
                public void delete(Judge0SubmissionToken token, Duration left) {
                    deletions.add(token);
                    usedStore.delete(token, left);
                }
            };
            var validator = new RunRequestValidator();
            var evaluator = new Judge0SuiteEvaluator(new SuiteReportParser());
            var initial = new JavaSubmitSuiteExecutor(new JavaSuitePlanner(validator, new SourceFileOverlay(validator)),
                    new JavaSuiteArchive(), engine, evaluator);
            long started = System.nanoTime();
            try (var session = Judge0CompiledJavaSubmission.open(request, initial, engine, recording, evaluator,
                    Duration.ofSeconds(180))) {
                var evaluation = session.evaluation();
                Duration left = Duration.ofNanos(Duration.ofSeconds(180).toNanos() - (System.nanoTime() - started));
                var benchmark = new JavaSubmitBenchmarkRunner().run(request, session, left);
                if (scenario.equals("wrong-answer")) {
                    assertThat(evaluation.status()).isEqualTo(Verdict.WRONG_ANSWER);
                    assertThat(benchmark.state()).isEqualTo(BenchmarkState.SKIPPED);
                    assertThat(fetched).isEmpty();
                } else if (scenario.equals("unavailable-artifacts")) {
                    assertThat(evaluation.status()).isEqualTo(Verdict.ACCEPTED);
                    assertThat(benchmark.state()).isEqualTo(BenchmarkState.FAILED);
                    assertThat(benchmark.metrics()).isNull();
                } else {
                    assertThat(evaluation.status()).isEqualTo(Verdict.ACCEPTED);
                    assertThat(benchmark.state()).isEqualTo(BenchmarkState.SUCCEEDED);
                    assertThat(benchmark.metrics().roundRuntimeMs()).hasSize(5).allMatch(value -> value >= 0);
                    assertThat(benchmark.metrics().peakMemoryBytes()).isPositive();
                    assertThat(fetched).hasSize(2);
                }
                assertThat(evaluation.testResults().getLast().input()).isNull();
                assertThat(evaluation.testResults().getLast().actualOutput()).isNull();
            } finally {
                for (var token : deletions) {
                    authorizedStore.delete(token, Duration.ofSeconds(5));
                    assertThatThrownBy(() -> authorizedStore.fetch(token, Duration.ofSeconds(5)))
                            .isInstanceOfSatisfying(Judge0ArtifactException.class,
                                    failure -> assertThat(failure.reason()).isEqualTo(Judge0ArtifactException.Reason.MISSING));
                }
            }
        }
    }
}
