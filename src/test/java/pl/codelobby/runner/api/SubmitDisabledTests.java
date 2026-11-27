package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import pl.codelobby.runner.core.AsyncSubmitCoordinator;
import pl.codelobby.runner.core.EvaluationExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "runner.internal-key=synthetic-internal-key-not-secret-123456")
class SubmitDisabledTests {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;

    @Test
    void disablesSubmitByDefaultWithoutCreatingBackgroundExecutionBeans() throws Exception {
        assertThat(context.getBeansOfType(AsyncSubmitCoordinator.class)).isEmpty();
        assertThat(context.getBeansOfType(EvaluationExecutor.class)).isEmpty();
        try (var client = HttpClient.newHttpClient()) {
            for (String method : new String[]{"POST", "GET"}) {
                String path = "/internal/v1/evaluation-jobs" + (method.equals("GET") ? "/missing" : "");
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("X-CodeLobby-Internal-Key", SubmitEndpointTests.KEY)
                        .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(Files.readString(Path.of("docs/api/examples/submit-request.json"))))
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(JsonMapper.builder().build().readTree(response.body()).get("code").asString())
                        .isEqualTo("EXECUTION_UNAVAILABLE");
                assertThat(response.headers().firstValue("Retry-After")).contains("1");
            }
        }
    }
}
