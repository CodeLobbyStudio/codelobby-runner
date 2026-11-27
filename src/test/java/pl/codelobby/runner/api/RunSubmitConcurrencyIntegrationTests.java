package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and Judge0. Requires an idle local Compose environment with the artifact overlay. */
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_MIXED_HTTP", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "runner.internal-key=synthetic-mixed-http-key-123456789012345",
        "runner.submit.enabled=true", "runner.submit.artifact-key=${CODELOBBY_ARTIFACT_KEY}",
        "runner.execution.max-active=2", "runner.execution.max-pending=1"})
class RunSubmitConcurrencyIntegrationTests {
    private static final String KEY = "synthetic-mixed-http-key-123456789012345";
    private static final String JOBS = "/internal/v1/evaluation-jobs";
    private final JsonMapper mapper = JsonMapper.builder().build();
    @LocalServerPort int port;

    @Test
    void mixesTwoRealSubmitsWithQueuedRunAndReusesCleanedSandboxes() throws Exception {
        Process configured = workerRuby("exit(File.read('/judge0.conf').lines.any? { |line| line.strip == 'COUNT=2' } ? 0 : 1)");
        assertThat(configured.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(configured.exitValue()).as("The mixed probe requires COUNT=2 in the mounted worker configuration").isZero();
        assertCleanWorker();
        String prefix = "mixed-" + UUID.randomUUID();
        String acceptedId = prefix + "-accepted";
        String wrongId = prefix + "-wrong";
        String runId = prefix + "-run";
        String accepted = payload("submit-request.json", acceptedId, false, true);
        String wrong = payload("submit-request.json", wrongId, true, true);
        // Observe populated kernel cgroups rather than inferring parallel sandboxes from HTTP state.
        Process overlap = observeOverlap();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(post(client, JOBS, accepted).statusCode()).isEqualTo(202);
            awaitState(client, acceptedId, "RUNNING");
            assertThat(post(client, JOBS, wrong).statusCode()).isEqualTo(202);
            awaitState(client, wrongId, "RUNNING");
            var run = client.sendAsync(request("/internal/v1/runs")
                    .POST(HttpRequest.BodyPublishers.ofString(payload("run-request.json", runId, false, true)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            Thread.sleep(500);
            assertThat(run.isDone()).as("Run must wait behind both active Submits").isFalse();
            var overflow = post(client, JOBS, payload("submit-request.json", prefix + "-overflow", false, false));
            assertThat(overflow.statusCode()).isEqualTo(429);
            assertThat(overflow.headers().firstValue("Retry-After")).contains("1");
            assertThat(json(overflow).get("code").asString()).isEqualTo("QUEUE_CAPACITY_EXCEEDED");
            assertThat(get(client, prefix + "-overflow").statusCode()).isEqualTo(404);
            assertThat(post(client, JOBS, accepted).statusCode()).isEqualTo(202);
            assertThat(post(client, JOBS, wrong).statusCode()).isEqualTo(202);
            assertThat(overlap.waitFor(40, TimeUnit.SECONDS)).isTrue();
            assertThat(overlap.exitValue()).as("Two sandbox cgroups must be populated simultaneously").isZero();
            assertThat(new String(overlap.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("TWO_POPULATED_SANDBOXES");
            System.out.println("Mixed HTTP probe: two populated sandbox cgroups observed");

            var wrongJob = awaitState(client, wrongId, "FINISHED");
            assertJob(wrongJob, wrongId, "WRONG_ANSWER", "SKIPPED");
            assertThat(wrongJob.get("result").get("testResults").get(0).get("actualOutput").asInt()).isZero();
            Process mixedOverlap = observeOverlap();
            try {
                assertThat(mixedOverlap.waitFor(40, TimeUnit.SECONDS)).isTrue();
                assertThat(mixedOverlap.exitValue()).as("Run and the remaining Submit must overlap in actual sandboxes").isZero();
            } finally {
                if (mixedOverlap.isAlive()) mixedOverlap.destroyForcibly();
            }
            System.out.println("Mixed HTTP probe: Run and Submit occupied populated cgroups simultaneously");
            var runResponse = run.get(60, TimeUnit.SECONDS);
            assertRun(runResponse, runId, "ACCEPTED", 3);
            assertThat(json(get(client, acceptedId)).get("state").asString()).isEqualTo("RUNNING");
            System.out.println("Mixed HTTP probe: queued Run finished while accepted Submit was still active");
            var acceptedJob = awaitState(client, acceptedId, "FINISHED");
            assertJob(acceptedJob, acceptedId, "ACCEPTED", "SUCCEEDED");
            assertThat(acceptedJob.get("result").get("benchmark").get("metrics").get("roundRuntimeMs").size())
                    .isEqualTo(5);
            assertThat(json(post(client, JOBS, accepted))).isEqualTo(acceptedJob);
            assertThat(json(post(client, JOBS, wrong))).isEqualTo(wrongJob);
            assertCleanWorker();
            assertRun(post(client, "/internal/v1/runs",
                    payload("run-request.json", prefix + "-after", true, false)), prefix + "-after", "WRONG_ANSWER", 0);
            assertCleanWorker();
            System.out.println("Mixed HTTP probe: correct isolated results, replay and cleanup, subsequent Run passed");
        } finally {
            if (overlap.isAlive()) overlap.destroyForcibly();
        }
    }

    private String payload(String file, String id, boolean wrong, boolean pause) throws Exception {
        var value = mapper.readTree(Files.readString(Path.of("docs/api/examples", file))).asObject();
        value.put(file.startsWith("submit") ? "submissionId" : "requestId", id);
        int sleepMillis = file.startsWith("run") ? 1800 : 650;
        String source = "public class Solution { public static int countVowels(String text) { "
                + (pause ? "try { Thread.sleep(" + sleepMillis + "); } catch (InterruptedException e) { throw new RuntimeException(e); } " : "")
                + (wrong ? "return 0;" : "return Helpers.count(text);") + " } }";
        value.get("submissionFiles").get(0).asObject().put("content", source);
        return mapper.writeValueAsString(value);
    }

    private JsonNode awaitState(HttpClient client, String id, String desired) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(185).toNanos();
        while (System.nanoTime() < deadline) {
            var response = get(client, id);
            assertThat(response.statusCode()).isEqualTo(200);
            var job = json(response);
            String state = job.get("state").asString();
            assertThat(state).as("Job " + id).isNotEqualTo("FAILED");
            if (state.equals(desired)) return job;
            assertThat(state).isIn("QUEUED", "RUNNING");
            assertThat(job.get("result").isNull()).isTrue();
            assertThat(job.get("error").isNull()).isTrue();
            Thread.sleep(100);
        }
        throw new AssertionError("Mixed HTTP job did not reach " + desired);
    }

    private void assertJob(JsonNode job, String id, String verdict, String benchmark) {
        assertThat(job.get("submissionId").asString()).isEqualTo(id);
        var result = job.get("result");
        assertThat(result.get("status").asString()).isEqualTo(verdict);
        assertThat(result.get("benchmark").get("state").asString()).isEqualTo(benchmark);
        assertThat(result.get("testResults").get(0).get("testId").asString()).isEqualTo("public-1");
        var hidden = result.get("testResults").get(1);
        assertThat(hidden.get("testId").asString()).isEqualTo("hidden-1");
        for (String field : new String[]{"input", "expectedOutput", "actualOutput", "stdout", "stderr"}) {
            assertThat(hidden.get(field).isNull()).isTrue();
        }
    }

    private void assertRun(HttpResponse<String> response, String id, String verdict, int output) {
        assertThat(response.statusCode()).isEqualTo(200);
        var run = json(response);
        assertThat(run.get("requestId").asString()).isEqualTo(id);
        assertThat(run.get("result").get("status").asString()).isEqualTo(verdict);
        assertThat(run.get("result").get("testResults").get(0).get("actualOutput").asInt()).isEqualTo(output);
    }

    private void assertCleanWorker() throws Exception {
        Process check = workerRuby("""
                deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 15
                loop do
                  groups = Dir.glob('/sys/fs/cgroup/codelobby-runner-judge0-v1/box-*')
                  journals = Dir.glob('/var/lib/codelobby-worker-state/jobs/*.json')
                  slots = Dir.glob('/var/lib/codelobby-worker-state/slots/*').any? { |path| File.size(path) > 0 }
                  mounts = File.read('/proc/self/mountinfo').include?('codelobby-workspace-')
                  if groups.empty? && journals.empty? && !slots && !mounts
                    puts 'CLEAN_WORKER'
                    exit 0
                  end
                  exit 1 if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
                  sleep 0.1
                end
                """);
        assertThat(check.waitFor(25, TimeUnit.SECONDS)).isTrue();
        assertThat(check.exitValue()).as("No retained sandbox cgroups, journals, reservations or workspace mounts").isZero();
    }

    private Process observeOverlap() throws Exception {
        return workerRuby("""
                deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 35
                loop do
                  groups = Dir.glob('/sys/fs/cgroup/codelobby-runner-judge0-v1/box-*/cgroup.events')
                  populated = groups.count do |path|
                    begin
                      File.read(path).lines.any? { |line| line.strip == 'populated 1' }
                    rescue Errno::ENOENT
                      false
                    end
                  end
                  if populated >= 2
                    puts 'TWO_POPULATED_SANDBOXES'
                    exit 0
                  end
                  exit 1 if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
                  sleep 0.05
                end
                """);
    }

    private Process workerRuby(String code) throws Exception {
        return new ProcessBuilder("docker", "compose", "--env-file", "judge0.conf", "-f", "compose.yaml",
                "-f", "compose.reports.yaml", "exec", "-T", "--user", "0", "workers", "ruby", "-e", code)
                .directory(Path.of("dev/judge0").toFile()).redirectErrorStream(true).start();
    }

    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private HttpResponse<String> get(HttpClient client, String id) throws Exception {
        return client.send(request(JOBS + "/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        return client.send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").header("X-CodeLobby-Internal-Key", KEY);
    }
}
