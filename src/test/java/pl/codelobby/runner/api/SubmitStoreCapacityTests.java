package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import pl.codelobby.runner.core.EvaluationJobStore;
import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.SubmitRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "runner.internal-key=synthetic-internal-key-not-secret-123456",
        "runner.submit.enabled=true", "runner.submit.artifact-key=synthetic-artifact-key-not-secret-123456",
        "runner.execution.max-active=1", "runner.execution.max-pending=0"})
@Import(SubmitEndpointTests.ControlledConfiguration.class)
class SubmitStoreCapacityTests {
    @LocalServerPort int port;
    @Autowired EvaluationJobStore jobs;
    @Autowired SubmitEndpointTests.ControlledExecutor executor;

    @Test
    void rejectsStorageOverflowButStillReplaysExistingJobsAndReportsConflicts() throws Exception {
        var mapper = JsonMapper.builder().build();
        var original = mapper.readValue(Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
        for (int index = 0; index < 100; index++) {
            var request = new SubmitRequest("stored-" + index, original.taskVersionId(), original.language(),
                    original.taskFiles(), original.submissionFiles(), original.testSuites(), original.limits());
            jobs.register(request);
            jobs.fail(request.submissionId(), new JobError("EXECUTION_UNAVAILABLE", "Execution unavailable.", true));
        }
        var json = mapper.valueToTree(original).asObject();
        try (var client = HttpClient.newHttpClient()) {
            json.put("submissionId", "overflow");
            var overflow = post(client, mapper.writeValueAsString(json));
            assertThat(overflow.statusCode()).isEqualTo(429);
            assertThat(overflow.headers().firstValue("Retry-After")).contains("1");
            assertThat(mapper.readTree(overflow.body()).get("code").asString()).isEqualTo("QUEUE_CAPACITY_EXCEEDED");
            assertThat(jobs.find("overflow")).isEmpty();
            json.put("submissionId", "stored-0");
            var replay = post(client, mapper.writeValueAsString(json));
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(replay.body()).get("state").asString()).isEqualTo("FAILED");
            json.get("submissionFiles").get(0).asObject().put("content", "changed-source");
            assertThat(post(client, mapper.writeValueAsString(json)).statusCode()).isEqualTo(409);
            assertThat(executor.calls.get()).isZero();
        }
    }

    private HttpResponse<String> post(HttpClient client, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/evaluation-jobs"))
                .header("X-CodeLobby-Internal-Key", SubmitEndpointTests.KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
