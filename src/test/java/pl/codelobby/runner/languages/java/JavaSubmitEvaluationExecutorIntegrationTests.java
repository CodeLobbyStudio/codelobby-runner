package pl.codelobby.runner.languages.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.AsyncSubmitCoordinator;
import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.InMemoryEvaluationJobStore;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.JobState;
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

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_SUBMIT_EXECUTOR", matches = "true")
class JavaSubmitEvaluationExecutorIntegrationTests {

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "wrong-answer", "compilation-error", "missing-artifacts", "cleanup-unavailable"})
    void producesFinalAsyncResultsAfterRealEvaluationBenchmarkAndCleanup(String scenario) throws Exception {
        var original = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
        String source = switch (scenario) {
            case "wrong-answer" -> "public class Solution { public static int countVowels(String text) { return 0; } }";
            case "compilation-error" -> "public class Solution { broken syntax }";
            default -> original.submissionFiles().getFirst().content();
        };
        var request = new SubmitRequest("executor-" + scenario, original.taskVersionId(), original.language(),
                original.taskFiles(), List.of(new SubmissionFile("src/Solution.java", source)),
                original.testSuites(), original.limits());
        var properties = new Judge0Properties(URI.create("http://127.0.0.1:2359"), 1001,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(200), Duration.ofSeconds(60));
        Set<Judge0SubmissionToken> tokens = ConcurrentHashMap.newKeySet();
        try (var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var client = new HttpJudge0Client(http, properties);
            var authorized = new HttpJudge0ArtifactStore(http, properties, System.getenv("CODELOBBY_ARTIFACT_KEY"));
            var artifacts = new Judge0ArtifactStore() {
                @Override
                public Judge0ArtifactSnapshot fetch(Judge0SubmissionToken token, Duration left) {
                    tokens.add(token);
                    if (scenario.equals("missing-artifacts")) {
                        throw new Judge0ArtifactException(Judge0ArtifactException.Reason.MISSING);
                    }
                    return authorized.fetch(token, left);
                }
                @Override
                public void awaitCleanup(Judge0SubmissionToken token, Duration left) {
                    tokens.add(token);
                    if (scenario.equals("cleanup-unavailable")) {
                        throw new Judge0ArtifactException(Judge0ArtifactException.Reason.UNAVAILABLE);
                    }
                    authorized.awaitCleanup(token, left);
                }
                @Override
                public void delete(Judge0SubmissionToken token, Duration left) {
                    tokens.add(token);
                    authorized.delete(token, left);
                }
            };
            var validator = new RunRequestValidator();
            var evaluator = new Judge0SuiteEvaluator(new SuiteReportParser());
            var initial = new JavaSubmitSuiteExecutor(new JavaSuitePlanner(validator, new SourceFileOverlay(validator)),
                    new JavaSuiteArchive(), client, evaluator);
            var execution = new JavaSubmitEvaluationExecutor(initial, client, artifacts, evaluator);
            var jobs = new InMemoryEvaluationJobStore();
            var queue = new ExecutionQueue(1, 1);
            try (var coordinator = new AsyncSubmitCoordinator(jobs, queue, execution)) {
                assertThat(coordinator.submit(request).created()).isTrue();
                assertThat(coordinator.submit(request).created()).isFalse();
                var job = awaitTerminal(jobs, request.submissionId());
                if (scenario.equals("cleanup-unavailable")) {
                    assertThat(job.state()).isEqualTo(JobState.FAILED);
                    assertThat(job.result()).isNull();
                    assertThat(job.error().code()).isEqualTo("EXECUTION_UNAVAILABLE");
                    assertThatThrownBy(() -> queue.reserve(Duration.ofSeconds(1)))
                            .isInstanceOf(ExecutionUnavailableException.class);
                } else {
                    assertThat(job.state()).isEqualTo(JobState.FINISHED);
                    var result = job.result();
                    assertThat(result.status()).isEqualTo(switch (scenario) {
                        case "wrong-answer" -> Verdict.WRONG_ANSWER;
                        case "compilation-error" -> Verdict.COMPILATION_ERROR;
                        default -> Verdict.ACCEPTED;
                    });
                    assertThat(result.benchmark().state()).isEqualTo(switch (scenario) {
                        case "accepted" -> BenchmarkState.SUCCEEDED;
                        case "missing-artifacts" -> BenchmarkState.FAILED;
                        default -> BenchmarkState.SKIPPED;
                    });
                    assertThat(result.totalTests()).isEqualTo(2);
                    assertThat(result.compileOutput()).isNull();
                    assertThat(result.testResults().getLast().input()).isNull();
                    assertThat(result.testResults().getLast().actualOutput()).isNull();
                    if (scenario.equals("accepted")) {
                        assertThat(result.passedTests()).isEqualTo(2);
                        assertThat(result.benchmark().metrics().roundRuntimeMs()).hasSize(5);
                    }
                    boolean capacityAvailable = queue.execute(Duration.ofSeconds(5), left -> true);
                    assertThat(capacityAvailable).isTrue();
                }
                assertThat(coordinator.submit(request).created()).isFalse();
                System.out.println("Submit executor probe finished: " + scenario + " / " + job.state());
            } finally {
                for (var token : tokens) {
                    authorized.delete(token, Duration.ofSeconds(5));
                }
            }
        }
    }

    private EvaluationJob awaitTerminal(InMemoryEvaluationJobStore store, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(185).toNanos();
        while (System.nanoTime() < deadline) {
            var job = store.find(id).orElseThrow();
            if (job.state() == JobState.FINISHED || job.state() == JobState.FAILED) return job;
            Thread.sleep(100);
        }
        throw new AssertionError("Real Submit job did not reach a terminal state");
    }
}
