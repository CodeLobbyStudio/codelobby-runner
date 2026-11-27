package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_SUBMIT_HTTP", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "runner.internal-key=synthetic-http-integration-key-12345678",
        "runner.submit.enabled=true", "runner.submit.artifact-key=${CODELOBBY_ARTIFACT_KEY}"})
class SubmitEndpointIntegrationTests {
    private static final String KEY = "synthetic-http-integration-key-12345678";
    @LocalServerPort int port;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "wrong-answer", "compilation-error"})
    void submitsPollsAndReplaysRealJobsThroughTheNewHttpApi(String scenario) throws Exception {
        var input = mapper.readTree(Files.readString(Path.of("docs/api/examples/submit-request.json"))).asObject();
        String id = "http-live-" + UUID.randomUUID();
        input.put("submissionId", id);
        if (!scenario.equals("accepted")) {
            input.get("submissionFiles").get(0).asObject().put("content", scenario.equals("wrong-answer")
                    ? "public class Solution { public static int countVowels(String text) { return 0; } }"
                    : "public class Solution { broken syntax }");
        }
        String body = mapper.writeValueAsString(input);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var accepted = post(client, body);
            assertThat(accepted.statusCode()).isEqualTo(202);
            String location = accepted.headers().firstValue("Location").orElseThrow();
            assertThat(location).isEqualTo("/internal/v1/evaluation-jobs/" + id);
            assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(post(client, body).statusCode()).isIn(200, 202);
            JsonNode job = awaitFinished(client, location);
            assertThat(job.get("submissionId").asString()).isEqualTo(id);
            assertThat(job.get("state").asString()).isEqualTo("FINISHED");
            var result = job.get("result");
            assertThat(result.get("status").asString()).isEqualTo(switch (scenario) {
                case "accepted" -> "ACCEPTED";
                case "wrong-answer" -> "WRONG_ANSWER";
                default -> "COMPILATION_ERROR";
            });
            assertThat(result.get("benchmark").get("state").asString())
                    .isEqualTo(scenario.equals("accepted") ? "SUCCEEDED" : "SKIPPED");
            assertThat(result.get("compileOutput").isNull()).isTrue();
            var hidden = result.get("testResults").get(1);
            for (String field : new String[]{"input", "expectedOutput", "actualOutput", "stdout", "stderr"}) {
                assertThat(hidden.get(field).isNull()).isTrue();
            }
            if (scenario.equals("accepted")) {
                assertThat(result.get("benchmark").get("metrics").get("roundRuntimeMs").size()).isEqualTo(5);
            }
            var replay = post(client, body);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.headers().firstValue("Location")).contains(location);
            assertThat(mapper.readTree(replay.body())).isEqualTo(job);
            input.get("submissionFiles").get(0).asObject().put("content", "different-payload");
            assertThat(post(client, mapper.writeValueAsString(input)).statusCode()).isEqualTo(409);
            System.out.println("Submit HTTP probe finished: " + scenario + " / FINISHED");
        }
    }

    private JsonNode awaitFinished(HttpClient client, String location) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(185).toNanos();
        while (System.nanoTime() < deadline) {
            var response = client.send(base(location).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var json = mapper.readTree(response.body());
            String state = json.get("state").asString();
            if (state.equals("FINISHED") || state.equals("FAILED")) return json;
            assertThat(json.get("result").isNull()).isTrue();
            assertThat(json.get("error").isNull()).isTrue();
            Thread.sleep(500);
        }
        throw new AssertionError("Real HTTP Submit job did not finish");
    }

    private HttpResponse<String> post(HttpClient client, String body) throws Exception {
        return client.send(base("/internal/v1/evaluation-jobs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("X-CodeLobby-Internal-Key", KEY);
    }
}
