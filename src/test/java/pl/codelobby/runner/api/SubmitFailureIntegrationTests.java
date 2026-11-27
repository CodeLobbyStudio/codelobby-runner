package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import pl.codelobby.runner.RunnerApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Destructive opt-in probes against an otherwise idle local Judge0 Compose project. */
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_SUBMIT_FAILURES", matches = "true")
class SubmitFailureIntegrationTests {
    private static final String KEY = "synthetic-submit-failure-key-123456789012345";
    private static final String JOBS = "/internal/v1/evaluation-jobs";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"server", "worker-deadline"})
    void failsWithoutPartialResultsQuarantinesAndRecoversAfterVerifiedRestart(String scenario) throws Exception {
        assertCleanWorker();
        String id = "failure-" + scenario + "-" + UUID.randomUUID();
        String body = payload("submit-request.json", id, true);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                var runner = startRunner()) {
            int port = port(runner);
            long admittedAt = System.nanoTime();
            assertThat(post(http, port, JOBS, body).statusCode()).isEqualTo(202);
            assertThat(post(http, port, JOBS, payload("submit-request.json", id + "-queued", true)).statusCode())
                    .isEqualTo(202);
            assertThat(json(get(http, port, id + "-queued")).get("state").asString()).isEqualTo("QUEUED");
            observeActiveSandbox();
            try {
                compose("kill", "-s", "SIGKILL", scenario.equals("server") ? "server" : "workers");
                var failed = terminal(http, port, id);
                assertFailed(failed, scenario.equals("server")
                        ? "EXECUTION_INFRASTRUCTURE_ERROR" : "REQUEST_DEADLINE_EXCEEDED");
                if (scenario.equals("worker-deadline")) {
                    double seconds = (System.nanoTime() - admittedAt) / 1_000_000_000.0;
                    assertThat(seconds).as("The real 180-second deadline must expire without a simulated clock")
                            .isBetween(175.0, 200.0);
                }
                var queued = terminal(http, port, id + "-queued");
                assertThat(queued.get("state").asString()).isEqualTo("FAILED");
                assertThat(queued.get("startedAt").isNull()).isTrue();
                assertThat(queued.get("result").isNull()).isTrue();
                assertThat(queued.get("error").get("code").asString())
                        .isIn("EXECUTION_UNAVAILABLE", "REQUEST_DEADLINE_EXCEEDED");
                assertThat(json(post(http, port, JOBS, body))).isEqualTo(failed);
                assertBlocked(http, port, id);
                System.out.println("Submit failure probe: " + scenario + " produced safe FAILED and quarantined admission");

                compose("up", "-d", "--wait", "--wait-timeout", "120", "server", "workers");
                assertCleanWorker();
                // Engine recovery is not permission to clear process-local runner quarantine automatically.
                assertBlocked(http, port, id);
                assertThat(json(get(http, port, id))).isEqualTo(failed);
                runner.close();
                try (var restarted = startRunner()) {
                    int newPort = port(restarted);
                    assertThat(get(http, newPort, id).statusCode()).isEqualTo(404);
                    String retry = id + "-retry";
                    assertThat(post(http, newPort, JOBS, payload("submit-request.json", retry, false)).statusCode())
                            .isEqualTo(202);
                    var recovered = terminal(http, newPort, retry);
                    assertThat(recovered.get("state").asString()).isEqualTo("FINISHED");
                    assertThat(recovered.get("result").get("status").asString()).isEqualTo("WRONG_ANSWER");
                    assertThat(recovered.get("result").get("benchmark").get("state").asString()).isEqualTo("SKIPPED");
                    var run = post(http, newPort, "/internal/v1/runs", payload("run-request.json", retry, false));
                    assertThat(run.statusCode()).isEqualTo(200);
                    assertThat(json(run).get("result").get("status").asString()).isEqualTo("ACCEPTED");
                    assertCleanWorker();
                }
                System.out.println("Submit failure probe: " + scenario + " recovered with fresh runner, Submit and Run passed");
            } finally {
                compose("up", "-d", "--wait", "--wait-timeout", "120", "server", "workers");
                assertCleanWorker();
            }
        }
    }

    private ConfigurableApplicationContext startRunner() {
        return new SpringApplicationBuilder(RunnerApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--runner.internal-key=" + KEY,
                "--runner.submit.enabled=true", "--runner.submit.artifact-key=${CODELOBBY_ARTIFACT_KEY}",
                "--runner.execution.max-active=1", "--runner.execution.max-pending=1",
                "--runner.judge0.connect-timeout=2s", "--runner.judge0.request-timeout=5s",
                "--runner.judge0.poll-interval=200ms", "--runner.judge0.result-timeout=200s");
    }

    private int port(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    private String payload(String file, String id, boolean pause) throws Exception {
        var value = mapper.readTree(Files.readString(Path.of("docs/api/examples", file))).asObject();
        value.put(file.startsWith("submit") ? "submissionId" : "requestId", id);
        String source = "public class Solution { public static int countVowels(String text) { "
                + (pause ? "try { Thread.sleep(1500); } catch (InterruptedException e) { throw new RuntimeException(e); } " : "")
                + (file.startsWith("submit") ? "return 0;" : "return Helpers.count(text);") + " } }";
        value.get("submissionFiles").get(0).asObject().put("content", source);
        return mapper.writeValueAsString(value);
    }

    private void assertFailed(JsonNode job, String code) {
        assertThat(job.get("state").asString()).isEqualTo("FAILED");
        assertThat(job.get("result").isNull()).isTrue();
        assertThat(job.get("startedAt").isNull()).isFalse();
        assertThat(job.get("finishedAt").isNull()).isFalse();
        assertThat(job.get("expiresAt").isNull()).isFalse();
        assertThat(job.get("error").get("code").asString()).isEqualTo(code);
        assertThat(job.get("error").get("retryable").asBoolean()).isTrue();
        assertThat(job.toString()).doesNotContain(KEY, "Thread.sleep", "Solution.java", "stackTrace");
    }

    private void assertBlocked(HttpClient client, int port, String id) throws Exception {
        var rejected = post(client, port, JOBS, payload("submit-request.json", id + "-blocked", false));
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(json(rejected).get("code").asString()).isEqualTo("EXECUTION_UNAVAILABLE");
        assertThat(get(client, port, id + "-blocked").statusCode()).isEqualTo(404);
        var run = post(client, port, "/internal/v1/runs", payload("run-request.json", id, false));
        assertThat(run.statusCode()).isEqualTo(503);
    }

    private JsonNode terminal(HttpClient client, int port, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(200).toNanos();
        while (System.nanoTime() < deadline) {
            var response = get(client, port, id);
            assertThat(response.statusCode()).isEqualTo(200);
            var job = json(response);
            String state = job.get("state").asString();
            if (state.equals("FINISHED") || state.equals("FAILED")) return job;
            assertThat(job.get("result").isNull()).isTrue();
            assertThat(job.get("error").isNull()).isTrue();
            Thread.sleep(200);
        }
        throw new AssertionError("Failure probe job did not become terminal");
    }

    private void observeActiveSandbox() throws Exception {
        workerRuby("""
                deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 25
                loop do
                  populated = Dir.glob('/sys/fs/cgroup/codelobby-runner-judge0-v1/box-*/cgroup.events').any? do |path|
                    begin
                      File.read(path).lines.any? { |line| line.strip == 'populated 1' }
                    rescue Errno::ENOENT
                      false
                    end
                  end
                  exit 0 if populated
                  exit 1 if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
                  sleep 0.05
                end
                """);
    }

    private void assertCleanWorker() throws Exception {
        workerRuby("""
                deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 25
                loop do
                  groups = Dir.glob('/sys/fs/cgroup/codelobby-runner-judge0-v1/box-*')
                  journals = Dir.glob('/var/lib/codelobby-worker-state/jobs/*.json')
                  slots = Dir.glob('/var/lib/codelobby-worker-state/slots/*').any? { |p| File.size(p) > 0 }
                  mounts = File.read('/proc/self/mountinfo').include?('codelobby-workspace-')
                  exit 0 if groups.empty? && journals.empty? && !slots && !mounts
                  exit 1 if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
                  sleep 0.1
                end
                """);
    }

    private void workerRuby(String script) throws Exception {
        compose("exec", "-T", "--user", "0", "workers", "ruby", "-e", script);
    }

    private void compose(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "--env-file", "judge0.conf", "-f", "compose.yaml",
                "-f", "compose.reports.yaml", "-f", "compose.artifacts.experimental.yaml"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(Path.of("dev/judge0").toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(150, TimeUnit.SECONDS)).as("Docker failure-probe operation must finish").isTrue();
            assertThat(process.exitValue()).as("Docker failure-probe operation must succeed").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private HttpResponse<String> get(HttpClient client, int port, String id) throws Exception {
        return client.send(request(port, JOBS + "/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, int port, String path, String body) throws Exception {
        return client.send(request(port, path).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(65))
                .header("Content-Type", "application/json").header("X-CodeLobby-Internal-Key", KEY);
    }
}
