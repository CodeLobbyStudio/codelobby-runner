package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_LOCAL_JUDGE0", matches = "true")
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JUDGE0_REPORTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runner.internal-key=synthetic-http-integration-key-123456789")
class RunEndpointIntegrationTests {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void localEngine(DynamicPropertyRegistry registry) {
        registry.add("runner.judge0.base-url",
                () -> System.getenv().getOrDefault("CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359"));
    }

    @ParameterizedTest
    @CsvSource({
            "ACCEPTED, ACCEPTED, 1",
            "WRONG_ANSWER, WRONG_ANSWER, 0",
            "COMPILATION_ERROR, NOT_RUN, 0",
            "TIME_LIMIT_EXCEEDED, NOT_RUN, 0"
    })
    void evaluatesJavaThroughTheAuthenticatedHttpEndpoint(
            Verdict verdict, TestVerdict caseVerdict, int passed) throws Exception {
        var mapper = JsonMapper.builder().build();
        var body = mapper.readTree(Path.of("docs/api/examples/run-request.json").toFile()).asObject();
        String source = switch (verdict) {
            case ACCEPTED -> body.get("submissionFiles").get(0).get("content").asString();
            case WRONG_ANSWER ->
                    "public class Solution { public static int countVowels(String text) { return 0; } }";
            case COMPILATION_ERROR -> "public class Solution { broken }";
            case TIME_LIMIT_EXCEEDED ->
                    "public class Solution { public static int countVowels(String text) { while (true) {} } }";
            default -> throw new AssertionError("Unexpected test verdict");
        };
        body.get("submissionFiles").get(0).asObject().put("content", source);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/runs"))
                .timeout(Duration.ofSeconds(70))
                .header("Content-Type", "application/json")
                .header("X-CodeLobby-Internal-Key", "synthetic-http-integration-key-123456789")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();

        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var result = mapper.readValue(response.body(), RunResponse.class);
            assertThat(result.requestId()).isEqualTo(body.get("requestId").asString());
            assertThat(result.taskVersionId()).isEqualTo(body.get("taskVersionId").asString());
            assertThat(result.result().status()).isEqualTo(verdict);
            assertThat(result.result().passedTests()).isEqualTo(passed);
            assertThat(result.result().totalTests()).isEqualTo(1);
            assertThat(result.result().testResults()).extracting(test -> test.status()).containsExactly(caseVerdict);
            assertThat(result.result().compileOutput()).isNull();
            assertThat(response.body()).doesNotContain("synthetic-http-integration-key", "public class",
                    "Files.writeString", "tests/PublicTests.java");
        }
    }
}
