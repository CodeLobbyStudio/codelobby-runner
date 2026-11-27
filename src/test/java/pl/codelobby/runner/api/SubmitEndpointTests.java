package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import pl.codelobby.runner.core.EvaluationExecutor;
import pl.codelobby.runner.core.EvaluationJobStore;
import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmitRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "runner.internal-key=synthetic-internal-key-not-secret-123456",
        "runner.submit.enabled=true",
        "runner.submit.artifact-key=synthetic-artifact-key-not-secret-123456",
        "runner.execution.max-active=1", "runner.execution.max-pending=1"})
@Import(SubmitEndpointTests.ControlledConfiguration.class)
class SubmitEndpointTests {
    static final String KEY = "synthetic-internal-key-not-secret-123456";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired ControlledExecutor executor;
    @Autowired EvaluationJobStore jobs;
    @Autowired ExecutionQueue queue;
    private String body;
    private String id;

    @BeforeEach
    void prepare() throws Exception {
        executor.gate.countDown();
        queue.execute(Duration.ofSeconds(5), left -> true);
        executor.calls.set(0);
        executor.gate = new CountDownLatch(0);
        id = "http-" + UUID.randomUUID();
        var json = mapper.readTree(Files.readString(Path.of("docs/api/examples/submit-request.json"))).asObject();
        json.put("submissionId", id);
        body = mapper.writeValueAsString(json);
    }

    @AfterEach
    void release() { executor.gate.countDown(); }

    @AfterAll
    static void closeClient() { CLIENT.close(); }

    @Test
    void createsPollsAndReplaysTerminalJobsThroughHttp() throws Exception {
        var created = post(body);
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(created.headers().firstValue("Location")).contains(path(id));
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        var job = terminal(id);
        assertThat(job.get("state").asString()).isEqualTo("FINISHED");
        assertThat(job.get("result").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(job.get("result").get("testResults").get(1).get("input").isNull()).isTrue();
        assertThat(job.get("createdAt").asString()).endsWith("Z");
        var replay = post(body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Location")).contains(path(id));
        assertThat(executor.calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Job:Part_1.2", "Case-Sensitive-ID", "1"})
    void returnedLocationReadsTheSameIdentifier(String nextId) throws Exception {
        String exactId = nextId + "-" + UUID.randomUUID();
        var response = post(withId(body, exactId));
        assertThat(response.statusCode()).isEqualTo(202);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo(path(exactId));
        var read = CLIENT.send(base(location).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(read.body()).get("submissionId").asString()).isEqualTo(exactId);
        terminal(exactId);
    }

    @Test
    void sharesQueueCapacityDeduplicatesAndHidesActivePartialResults() throws Exception {
        executor.gate = new CountDownLatch(1);
        try {
            assertThat(post(body).statusCode()).isEqualTo(202);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (executor.calls.get() < 1 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(executor.calls.get()).isEqualTo(1);
            var running = mapper.readTree(get(id).body());
            assertThat(running.get("state").asString()).isEqualTo("RUNNING");
            assertThat(running.get("result").isNull()).isTrue();
            assertThat(running.get("error").isNull()).isTrue();
            String pendingId = id + "-pending";
            String pending = withId(body, pendingId);
            assertThat(post(pending).statusCode()).isEqualTo(202);
            assertThat(mapper.readTree(get(pendingId).body()).get("state").asString()).isEqualTo("QUEUED");
            assertThat(post(pending).statusCode()).isEqualTo(202);
            var changed = mapper.readTree(pending).asObject();
            changed.get("submissionFiles").get(0).asObject().put("content", "changed-source");
            assertError(post(mapper.writeValueAsString(changed)), 409, "SUBMISSION_ID_CONFLICT");
            var rejected = post(withId(body, id + "-overflow"));
            assertError(rejected, 429, "QUEUE_CAPACITY_EXCEEDED");
            assertThat(rejected.headers().firstValue("Retry-After")).contains("1");
            assertThat(jobs.find(id + "-overflow")).isEmpty();
            var runRequest = base("/internal/v1/runs").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Files.readString(
                            Path.of("docs/api/examples/run-request.json")))).build();
            assertError(CLIENT.send(runRequest, HttpResponse.BodyHandlers.ofString()),
                    429, "QUEUE_CAPACITY_EXCEEDED");
            executor.gate.countDown();
            terminal(id);
            terminal(pendingId);
            assertThat(executor.calls.get()).isEqualTo(2);
        } finally {
            executor.gate.countDown();
        }
    }

    @Test
    void authenticatesBothRoutesBeforeParsing() throws Exception {
        for (String method : new String[]{"GET", "POST"}) {
            var request = HttpRequest.newBuilder(uri(method.equals("GET") ? path(id) : "/internal/v1/evaluation-jobs"))
                    .header("Content-Type", "application/json")
                    .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString("sensitive-invalid-json")).build();
            var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            assertError(response, 401, "UNAUTHORIZED");
            assertThat(response.body()).doesNotContain("sensitive-invalid-json");
        }
        var duplicated = base("/internal/v1/evaluation-jobs").header("X-CodeLobby-Internal-Key", KEY)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertError(CLIENT.send(duplicated, HttpResponse.BodyHandlers.ofString()), 401, "UNAUTHORIZED");
        assertThat(executor.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"submissionId", "taskVersionId", "language", "taskFiles", "submissionFiles", "testSuites", "limits"})
    void rejectsMissingAndNullRequiredFieldsBeforeAdmission(String field) throws Exception {
        var missing = mapper.readTree(body).asObject();
        missing.remove(field);
        assertError(post(mapper.writeValueAsString(missing)), 400, "INVALID_REQUEST");
        var absent = mapper.readTree(body).asObject();
        absent.putNull(field);
        assertError(post(mapper.writeValueAsString(absent)), 400, "INVALID_REQUEST");
        assertThat(jobs.find(id)).isEmpty();
    }

    @Test
    void rejectsMalformedUnknownDuplicateAndCoercedJson() throws Exception {
        for (String value : new String[]{"null", "[]", "invalid-json", body + "{}",
                body.replaceFirst("\\{", "{\"unknown\":true,"),
                body.replaceFirst("\\{", "{\"submissionId\":\"duplicate\","),
                body.replace("\"cpuTimeMs\":2000", "\"cpuTimeMs\":2000.5")}) {
            assertError(post(value), 400, "INVALID_REQUEST");
        }
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsProtectedFileOverridesAndUnsupportedLanguageBeforeScheduling() throws Exception {
        var protectedFile = mapper.readTree(body).asObject();
        protectedFile.get("submissionFiles").get(0).asObject().put("path", "tests/HiddenTests.java");
        assertError(post(mapper.writeValueAsString(protectedFile)), 400, "INVALID_SOURCE_FILES");
        var cpp = mapper.readTree(Files.readString(Path.of("docs/api/examples/run-request-cpp.json"))).asObject();
        cpp.remove("requestId");
        cpp.put("submissionId", id);
        assertError(post(mapper.writeValueAsString(cpp)), 400, "INVALID_REQUEST");
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void returnsSafeNotFoundAndRejectsInvalidIdentifiers() throws Exception {
        assertError(get(id), 404, "JOB_NOT_FOUND");
        var invalid = CLIENT.send(base(path("bad%20id")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertError(invalid, 400, "INVALID_REQUEST");
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void boundsBodiesAndRejectsUnsupportedEncoding() throws Exception {
        assertError(post("x".repeat(2000001)), 413, "PAYLOAD_TOO_LARGE");
        var encoded = base("/internal/v1/evaluation-jobs").header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertError(CLIENT.send(encoded, HttpResponse.BodyHandlers.ofString()), 415, "INVALID_REQUEST");
        var invalidUtf8 = base("/internal/v1/evaluation-jobs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xff})).build();
        assertError(CLIENT.send(invalidUtf8, HttpResponse.BodyHandlers.ofString()), 400, "INVALID_REQUEST");
    }

    private String withId(String value, String next) {
        var json = mapper.readTree(value).asObject();
        json.put("submissionId", next);
        return mapper.writeValueAsString(json);
    }

    private JsonNode terminal(String submissionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var response = get(submissionId);
            assertThat(response.statusCode()).isEqualTo(200);
            var json = mapper.readTree(response.body());
            if (json.get("state").asString().equals("FINISHED") || json.get("state").asString().equals("FAILED")) return json;
            Thread.sleep(10);
        }
        throw new AssertionError("Controlled HTTP job did not finish");
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(KEY, "sensitive", "changed-source", "HiddenTests.java");
    }

    private HttpResponse<String> post(String value) throws Exception {
        return CLIENT.send(base("/internal/v1/evaluation-jobs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(value)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String value) throws Exception {
        return CLIENT.send(base(path(value)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).header("X-CodeLobby-Internal-Key", KEY);
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String path(String value) { return "/internal/v1/evaluation-jobs/" + value; }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledConfiguration {
        @Bean @Primary ControlledExecutor controlledSubmitExecutor() { return new ControlledExecutor(); }
    }

    static final class ControlledExecutor implements EvaluationExecutor {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch gate = new CountDownLatch(0);
        private final EvaluationResult result = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/job-finished.json").toFile(), EvaluationJob.class).result();
        @Override public EvaluationResult evaluate(SubmitRequest input, Duration left) {
            calls.incrementAndGet();
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("Controlled executor gate timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return result;
        }
    }
}
