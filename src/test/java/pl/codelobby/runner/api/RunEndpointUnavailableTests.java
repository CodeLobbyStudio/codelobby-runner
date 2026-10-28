package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runner.internal-key=synthetic-test-key-not-a-secret-123456")
class RunEndpointUnavailableTests {

    @LocalServerPort
    private int port;

    @Test
    void defaultApplicationDoesNotPretendToEvaluateCode() throws Exception {
        var body = Files.readString(Path.of("docs/api/examples/run-request.json"));
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/runs"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("X-CodeLobby-Internal-Key", "synthetic-test-key-not-a-secret-123456")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).contains("1");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var json = JsonMapper.builder().build().readTree(response.body());
            assertThat(json.size()).isEqualTo(3);
            assertThat(json.get("code").asString()).isEqualTo("EXECUTION_UNAVAILABLE");
            assertThat(json.get("retryable").asBoolean()).isTrue();
        }
    }
}
