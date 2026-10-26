package pl.codelobby.runner.api;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import pl.codelobby.runner.core.SubmissionExecutor;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.RunResult;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runner.internal-key=synthetic-test-key-not-a-secret-123456")
@Import(RunEndpointTests.ExecutorConfiguration.class)
class RunEndpointTests {

    private static final String KEY = "synthetic-test-key-not-a-secret-123456";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    @LocalServerPort
    private int port;
    @Autowired
    private RecordingExecutor executor;
    private String body;

    @BeforeEach
    void prepare() throws Exception {
        body = Files.readString(Path.of("docs/api/examples/run-request.json"));
        executor.calls.set(0);
        executor.request = null;
        executor.failure = null;
        executor.result = mapper.readValue(Path.of("docs/api/examples/run-accepted.json").toFile(), RunResponse.class).result();
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-accepted.json", "run-wrong-answer.json", "run-compilation-error.json"})
    void returnsContractResponsesThroughRealHttpAndUseCase(String example) throws Exception {
        var expected = mapper.readTree(Path.of("docs/api/examples", example).toFile());
        executor.result = mapper.treeToValue(expected, RunResponse.class).result();

        var response = post(body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(mapper.readTree(response.body())).isEqualTo(expected);
        assertThat(executor.calls.get()).isEqualTo(1);
        assertThat(executor.request.requestId()).isEqualTo("run-001");
    }

    @Test
    void authenticatesBeforeParsingAndDoesNotEchoSecrets() throws Exception {
        for (String key : new String[] {"", "wrong-secret"}) {
            var builder = baseRequest().POST(HttpRequest.BodyPublishers.ofString("invalid-json-sensitive-source"));
            if (!key.isEmpty()) builder.header("X-CodeLobby-Internal-Key", key);
            var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertError(response, 401, "UNAUTHORIZED", false);
            assertThat(response.body()).doesNotContain("wrong-secret", "sensitive-source");
        }
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsRepeatedKeyHeaders() throws Exception {
        var response = CLIENT.send(baseRequest().header("X-CodeLobby-Internal-Key", KEY)
                .header("X-CodeLobby-Internal-Key", KEY).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(response, 401, "UNAUTHORIZED", false);
        assertThat(executor.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "taskVersionId", "language", "taskFiles", "submissionFiles", "testSuites", "limits"})
    void rejectsMissingAndNullRequiredFields(String field) throws Exception {
        var json = mapper.readTree(body).asObject();
        json.remove(field);
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        json.putNull(field);
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "[]", "{}", "{\"requestId\":\"a\",\"requestId\":\"b\"}"})
    void rejectsMalformedOrIncompleteJson(String json) throws Exception {
        assertError(post(json), 400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsNestedDuplicatesUnknownPropertiesAndTrailingJson() throws Exception {
        assertError(post(body.replace("\"cpuTimeMs\": 2000", "\"cpuTimeMs\": 2000, \"cpuTimeMs\": 1")),
                400, "INVALID_REQUEST", false);
        var json = mapper.readTree(body).asObject();
        json.put("unexpected", "sensitive-source");
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        json.remove("unexpected");
        json.get("taskFiles").get(0).asObject().put("unknown", "sensitive-source");
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        assertError(post(body + " {}"), 400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.5", "2e3", "\"2000\"", "true", "null"})
    void rejectsNonIntegerBudgetValues(String token) throws Exception {
        assertError(post(body.replace("\"cpuTimeMs\": 2000", "\"cpuTimeMs\": " + token)),
                400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsNonStringIdentifiersAndMissingNestedFields() throws Exception {
        var json = mapper.readTree(body).asObject();
        json.put("requestId", 123);
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        json = mapper.readTree(body).asObject();
        json.get("limits").asObject().remove("cpuTimeMs");
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        json = mapper.readTree(body).asObject();
        json.get("taskFiles").get(0).asObject().putNull("content");
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"language", "role", "visibility"})
    void rejectsEnumOrdinalsInsteadOfStringNames(String field) throws Exception {
        var json = mapper.readTree(body).asObject();
        switch (field) {
            case "language" -> json.put("language", 0);
            case "role" -> json.get("taskFiles").get(0).asObject().put("type", 0);
            case "visibility" -> json.get("testSuites").get(0).asObject().put("visibility", 0);
            default -> throw new AssertionError("Unknown fixture field");
        }
        assertError(post(mapper.writeValueAsString(json)), 400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsARequestWithInvalidBudgetsBeforeExecution() throws Exception {
        assertError(post(body.replace("\"cpuTimeMs\": 2000", "\"cpuTimeMs\": 2001")),
                400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsUnsupportedMethodsAndKeepsUnknownRoutesAsNotFound() throws Exception {
        var response = CLIENT.send(baseRequest().header("X-CodeLobby-Internal-Key", KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(response, 405, "INVALID_REQUEST", false);
        var missing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/not-implemented"))
                .header("X-CodeLobby-Internal-Key", KEY).GET().build();
        assertError(CLIENT.send(missing, HttpResponse.BodyHandlers.ofString()), 404, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void mapsSemanticFileAndSizeErrorsWithoutLeakingSource() throws Exception {
        var invalidPath = body.replace("src/Solution.java", "../private-source.java");
        var response = post(invalidPath);
        assertError(response, 400, "INVALID_SOURCE_FILES", false);
        assertThat(response.body()).doesNotContain("private-source");
        var json = mapper.readTree(body).asObject();
        json.get("submissionFiles").get(0).asObject().put("content", "x".repeat(131073));
        assertError(post(mapper.writeValueAsString(json)), 413, "PAYLOAD_TOO_LARGE", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void acceptsExactlyTheBodyLimitAndRejectsOneByteMore() throws Exception {
        String boundary = body + " ".repeat(RunController.MAX_REQUEST_BYTES - body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(post(boundary).statusCode()).isEqualTo(200);
        executor.calls.set(0);
        assertError(post(boundary + " "), 413, "PAYLOAD_TOO_LARGE", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void enforcesTheBodyLimitForChunkedRequestsWithoutContentLength() throws Exception {
        byte[] oversized = " ".repeat(RunController.MAX_REQUEST_BYTES + 1).getBytes(StandardCharsets.UTF_8);
        var response = CLIENT.send(baseRequest().header("X-CodeLobby-Internal-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(response, 413, "PAYLOAD_TOO_LARGE", false);
        assertThat(executor.calls.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"text/plain,identity", "application/json;charset=ISO-8859-1,identity", "application/json,gzip"})
    void rejectsUnsupportedMediaOrEncoding(String contentType, String encoding) throws Exception {
        var request = HttpRequest.newBuilder(uri()).timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType).header("Content-Encoding", encoding)
                .header("X-CodeLobby-Internal-Key", KEY).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertError(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()), 415, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsMalformedUtf8() throws Exception {
        var response = CLIENT.send(baseRequest().header("X-CodeLobby-Internal-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xC3, 0x28})).build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(response, 400, "INVALID_REQUEST", false);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void returnsGenericErrorsForExecutorFailureAndNullResult() throws Exception {
        executor.failure = new IllegalStateException("private-source.java: secret assertion and key");
        var response = post(body);
        assertError(response, 500, "INTERNAL_SERVER_ERROR", false);
        assertThat(response.body()).doesNotContain("private-source", "assertion", KEY);
        executor.failure = null;
        executor.result = null;
        assertError(post(body), 500, "INTERNAL_SERVER_ERROR", false);
    }

    @Test
    void executorJsonFailureIsNotBlamedOnTheRequest() throws Exception {
        try {
            mapper.readTree("{invalid-private-engine-report");
        } catch (JacksonException exception) {
            executor.failure = exception;
        }
        assertThat(executor.failure).isNotNull();
        var response = post(body);
        assertError(response, 500, "INTERNAL_SERVER_ERROR", false);
        assertThat(response.body()).doesNotContain("private-engine-report");
    }

    private HttpResponse<String> post(String json) throws Exception {
        return CLIENT.send(baseRequest().header("X-CodeLobby-Internal-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder baseRequest() {
        return HttpRequest.newBuilder(uri()).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json");
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + port + "/internal/v1/runs");
    }

    private void assertError(HttpResponse<String> response, int status, String code, boolean retryable) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var json = mapper.readTree(response.body());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("code").asString()).isEqualTo(code);
        assertThat(json.get("message").isString()).isTrue();
        assertThat(json.get("retryable").asBoolean()).isEqualTo(retryable);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExecutorConfiguration {
        @Bean
        @Primary
        RecordingExecutor testExecutor() {
            return new RecordingExecutor();
        }
    }

    static final class RecordingExecutor implements SubmissionExecutor {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile RunRequest request;
        private volatile RunResult result;
        private volatile RuntimeException failure;

        @Override
        public RunResult execute(RunRequest input) {
            calls.incrementAndGet();
            request = input;
            if (failure != null) throw failure;
            return result;
        }
    }
}
