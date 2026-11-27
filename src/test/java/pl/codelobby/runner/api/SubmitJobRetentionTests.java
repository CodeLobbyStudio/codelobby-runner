package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import pl.codelobby.runner.core.EvaluationJobStore;
import pl.codelobby.runner.core.InMemoryEvaluationJobStore;
import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.SubmitRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and store with a controlled wall clock and a test-only executor. No Judge0 or real-time wait. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "runner.internal-key=synthetic-internal-key-not-secret-123456",
        "runner.submit.enabled=true", "runner.submit.artifact-key=synthetic-artifact-key-not-secret-123456",
        "runner.execution.max-active=1", "runner.execution.max-pending=1"})
@Import({SubmitEndpointTests.ControlledConfiguration.class, SubmitJobRetentionTests.StoreConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SubmitJobRetentionTests {
    private static final Instant START = Instant.parse("2026-11-27T12:00:00Z");
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SubmitRequest original = mapper.readValue(
            Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
    @LocalServerPort int port;
    @Autowired MutableClock clock;
    @Autowired EvaluationJobStore jobs;
    @Autowired SubmitEndpointTests.ControlledExecutor executor;

    @AfterAll
    static void closeClient() { CLIENT.close(); }

    @Test
    void finishedJobExpiresExactlyAtItsDeadlineWithoutReplayExtendingRetention() throws Exception {
        var request = withId("finished-boundary");
        assertThat(post(request).statusCode()).isEqualTo(202);
        var finished = terminal(request.submissionId());
        assertThat(finished.get("state").asString()).isEqualTo("FINISHED");
        Instant expires = Instant.parse(finished.get("expiresAt").asString());
        assertThat(expires).isEqualTo(Instant.parse(finished.get("finishedAt").asString()).plusSeconds(1800));
        clock.set(expires.minusNanos(1));
        assertThat(json(get(request.submissionId()))).isEqualTo(finished);
        var replay = post(request);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json(replay)).isEqualTo(finished);
        assertThat(executor.calls.get()).isEqualTo(1);
        clock.set(expires);
        assertNotFound(request.submissionId());
        assertNotFound(request.submissionId());
        assertThat(executor.calls.get()).isEqualTo(1);
    }

    @Test
    void failedRetentionStartsAtCompletionRatherThanCreation() throws Exception {
        var request = withId("failed-boundary");
        jobs.register(request);
        jobs.start(request.submissionId());
        clock.set(START.plusSeconds(30));
        var failed = jobs.fail(request.submissionId(), new JobError("EXECUTION_UNAVAILABLE", "Execution unavailable.", true));
        assertThat(failed.expiresAt()).isEqualTo(START.plusSeconds(1830));
        clock.set(START.plusSeconds(1800));
        assertThat(get(request.submissionId()).statusCode()).isEqualTo(200);
        clock.set(failed.expiresAt().minusNanos(1));
        var before = get(request.submissionId());
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(json(before).get("state").asString()).isEqualTo("FAILED");
        assertThat(json(before).get("result").isNull()).isTrue();
        clock.set(failed.expiresAt());
        assertNotFound(request.submissionId());
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void retentionDoesNotPurgeQueuedOrRunningSnapshots() throws Exception {
        // Seed snapshots directly to isolate retention from the coordinator's separate 180-second deadline.
        seedActiveJobs();
        clock.set(START.plusSeconds(7200));
        assertActive("retained-queued", "QUEUED");
        assertActive("retained-running", "RUNNING");
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void expiryFreesCapacityAndRemovesFingerprintsWhilePreservingActiveSnapshots() throws Exception {
        seedActiveJobs();
        for (int index = 0; index < 98; index++) {
            var request = withId("expired-" + index);
            jobs.register(request);
            jobs.fail(request.submissionId(), new JobError("EXECUTION_UNAVAILABLE", "Execution unavailable.", true));
        }
        var next = withId("expired-0");
        var changed = mapper.valueToTree(next).asObject();
        changed.get("submissionFiles").get(0).asObject().put("content",
                original.submissionFiles().getFirst().content() + "\n// a new attempt after expiry\n");
        String body = mapper.writeValueAsString(changed);
        clock.set(START.plusSeconds(1800).minusNanos(1));
        var conflict = post(body);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).get("code").asString()).isEqualTo("SUBMISSION_ID_CONFLICT");
        var full = post(withId("new-while-full"));
        assertThat(full.statusCode()).isEqualTo(429);
        assertThat(json(full).get("code").asString()).isEqualTo("QUEUE_CAPACITY_EXCEEDED");
        assertThat(full.headers().firstValue("Retry-After")).contains("1");
        assertThat(jobs.find("new-while-full")).isEmpty();
        assertThat(executor.calls.get()).isZero();

        clock.set(START.plusSeconds(1800));
        var created = post(body);
        assertThat(created.statusCode()).isEqualTo(202);
        var fresh = terminal("expired-0");
        assertThat(fresh.get("state").asString()).isEqualTo("FINISHED");
        assertThat(Instant.parse(fresh.get("createdAt").asString())).isEqualTo(clock.instant());
        assertThat(Instant.parse(fresh.get("expiresAt").asString())).isEqualTo(clock.instant().plusSeconds(1800));
        assertThat(executor.calls.get()).isEqualTo(1);
        assertNotFound("expired-1");
        assertActive("retained-queued", "QUEUED");
        assertActive("retained-running", "RUNNING");
        var replay = post(body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json(replay)).isEqualTo(fresh);
        assertThat(executor.calls.get()).isEqualTo(1);
    }

    private void seedActiveJobs() {
        jobs.register(withId("retained-queued"));
        jobs.register(withId("retained-running"));
        jobs.start("retained-running");
    }

    private void assertActive(String id, String state) throws Exception {
        var response = get(id);
        assertThat(response.statusCode()).isEqualTo(200);
        var snapshot = json(response);
        assertThat(snapshot.get("state").asString()).isEqualTo(state);
        assertThat(snapshot.get("expiresAt").isNull()).isTrue();
        assertThat(snapshot.get("result").isNull()).isTrue();
        assertThat(snapshot.get("error").isNull()).isTrue();
    }

    private void assertNotFound(String id) throws Exception {
        var response = get(id);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("JOB_NOT_FOUND");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(SubmitEndpointTests.KEY);
    }

    private JsonNode terminal(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var response = get(id);
            assertThat(response.statusCode()).isEqualTo(200);
            var snapshot = json(response);
            if (snapshot.get("state").asString().equals("FINISHED")) return snapshot;
            assertThat(snapshot.get("state").asString()).isIn("QUEUED", "RUNNING");
            Thread.sleep(10);
        }
        throw new AssertionError("Test Submit job did not finish");
    }

    private SubmitRequest withId(String id) {
        return new SubmitRequest(id, original.taskVersionId(), original.language(), original.taskFiles(),
                original.submissionFiles(), original.testSuites(), original.limits());
    }

    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private HttpResponse<String> get(String id) throws Exception {
        return CLIENT.send(base("/internal/v1/evaluation-jobs/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(SubmitRequest input) throws Exception { return post(mapper.writeValueAsString(input)); }
    private HttpResponse<String> post(String input) throws Exception {
        return CLIENT.send(base("/internal/v1/evaluation-jobs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(input)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5))
                .header("X-CodeLobby-Internal-Key", SubmitEndpointTests.KEY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StoreConfiguration {
        @Bean MutableClock retentionClock() { return new MutableClock(new AtomicReference<>(START), ZoneOffset.UTC); }
        @Bean @Primary EvaluationJobStore retentionStore(MutableClock clock) { return new InMemoryEvaluationJobStore(clock); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;
        MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId value) { return new MutableClock(now, value); }
    }
}
