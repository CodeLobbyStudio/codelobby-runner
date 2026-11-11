package pl.codelobby.runner.sandbox.judge0;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.ExecutionLimits;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.codelobby.runner.sandbox.judge0.Judge0ClientException.Reason;

class HttpJudge0ClientTests {

    private static final String TOKEN = "d85cd024-1548-4165-96c7-7bc88673f194";
    private static final String TOKEN_JSON = "{\"token\":\"" + TOKEN + "\"}";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger requests = new AtomicInteger();
    private final CompletableFuture<CapturedRequest> captured = new CompletableFuture<>();
    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpClient http;
    private URI origin;
    private HttpHandler response;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        response = exchange -> reply(exchange, 201, "application/json", TOKEN_JSON);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            captured.complete(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Accept"), exchange.getRequestBody().readAllBytes()));
            try {
                response.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        http = new Judge0Configuration().judge0HttpClient(properties(Duration.ofSeconds(2)));
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop(0);
        serverThreads.shutdownNow();
        assertThat(serverThreads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        http.close();
    }

    @Test
    void sendsConfiguredJavaProgramAndConservativeBudgetsAndReturnsToken() throws Exception {
        var submission = new Judge0Submission("class Main { /* zażółć 😀 */ }", "żółw\n",
                new ExecutionLimits(1501, 2501, 127999999));
        var properties = new Judge0Properties(origin, 1234, Duration.ofMillis(100), Duration.ofSeconds(2),
                Duration.ofMillis(50), Duration.ofSeconds(5));

        var token = new HttpJudge0Client(http, properties).submitJava(submission);

        assertThat(token.value()).isEqualTo(TOKEN);
        var request = captured.get(2, TimeUnit.SECONDS);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("/submissions?base64_encoded=true&wait=false");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.accept()).isEqualTo("application/json");
        var json = mapper.readTree(request.body());
        assertThat(decode(json.get("source_code").asString())).isEqualTo(submission.sourceCode());
        assertThat(decode(json.get("stdin").asString())).isEqualTo(submission.stdin());
        assertThat(json.get("language_id").asInt()).isEqualTo(1234);
        assertThat(json.get("cpu_time_limit").asDouble()).isEqualTo(1.501);
        assertThat(json.get("wall_time_limit").asDouble()).isEqualTo(2.501);
        assertThat(json.get("memory_limit").asLong()).isEqualTo(124999);
        assertThat(json.get("cpu_extra_time").asInt()).isZero();
        assertThat(json.get("enable_network").asBoolean()).isFalse();
        assertThat(json.get("number_of_runs").asInt()).isEqualTo(1);
        assertThat(json.has("expected_output")).isFalse();
        assertThat(json.has("callback_url")).isFalse();
        assertThat(requests).hasValue(1);
    }

    @Test
    void sendsArchiveAsAdditionalFilesWithTheProjectLanguageAndReturnsToken() throws Exception {
        byte[] zip = {0x50, 0x4b, 0x03, 0x04, (byte) 0xff};

        var token = client(Duration.ofSeconds(2)).submitProject(zip, new ExecutionLimits(1501, 2501, 127999999));

        assertThat(token.value()).isEqualTo(TOKEN);
        var request = captured.get(2, TimeUnit.SECONDS);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("/submissions?base64_encoded=true&wait=false");
        var json = mapper.readTree(request.body());
        assertThat(Base64.getDecoder().decode(json.get("additional_files").asString())).isEqualTo(zip);
        assertThat(json.get("language_id").asInt()).isEqualTo(89);
        assertThat(json.get("cpu_time_limit").asDouble()).isEqualTo(1.501);
        assertThat(json.get("wall_time_limit").asDouble()).isEqualTo(2.501);
        assertThat(json.get("memory_limit").asLong()).isEqualTo(124999);
        assertThat(json.get("enable_network").asBoolean()).isFalse();
        assertThat(json.has("source_code")).isFalse();
        assertThat(json.has("stdin")).isFalse();
        assertThat(requests).hasValue(1);
    }

    @Test
    void readsExternalCompilationMeasurementsAlongsideTheSeparateReport() {
        response = exchange -> reply(exchange, 200, "application/json",
                "{\"state\":\"MISSING\",\"compilation\":{\"cpuTimeMs\":123.5,\"wallTimeMs\":300.25}}");

        var report = client(Duration.ofSeconds(2)).getSuiteReport(new Judge0SubmissionToken(TOKEN));

        assertThat(report.state()).isEqualTo(Judge0SuiteReport.State.MISSING);
        assertThat(report.compilation()).isEqualTo(new Judge0CompilationUsage(123.5, 300.25));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"cpuTimeMs\":-1,\"wallTimeMs\":2}",
            "{\"cpuTimeMs\":1}",
            "{\"cpuTimeMs\":\"1\",\"wallTimeMs\":2}",
            "{\"cpuTimeMs\":1e9999,\"wallTimeMs\":2}"
    })
    void rejectsInvalidCompilationMeasurements(String compilation) {
        response = exchange -> reply(exchange, 200, "application/json",
                "{\"state\":\"MISSING\",\"compilation\":" + compilation + "}");
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getSuiteReport(new Judge0SubmissionToken(TOKEN)))
                .isInstanceOfSatisfying(Judge0ClientException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    @Test
    void boundsTheProjectPostByTheRemainingOperationDeadlineWithoutRetry() {
        response = exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            reply(exchange, 201, "application/json", TOKEN_JSON);
        };
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).submitProject(
                new byte[] {1}, new ExecutionLimits(2000, 5000, 128000000), Duration.ofMillis(50)))
                .isInstanceOfSatisfying(Judge0ClientException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT));
        assertThat(requests.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void preservesOneMillisecondBudgetAndEmptyStdin() throws Exception {
        client(Duration.ofSeconds(2)).submitJava(new Judge0Submission("class Main {}", "",
                new ExecutionLimits(1, 1, 16000000)));
        var json = mapper.readTree(captured.get(2, TimeUnit.SECONDS).body());
        assertThat(json.get("cpu_time_limit").asDouble()).isEqualTo(0.001);
        assertThat(json.get("wall_time_limit").asDouble()).isEqualTo(0.001);
        assertThat(json.get("memory_limit").asLong()).isEqualTo(15625);
        assertThat(json.get("stdin").asString()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 302, 401, 403, 422, 429, 500, 503})
    void rejectsUnexpectedHttpStatusWithoutRetryOrReturningServerDiagnostics(int status) {
        response = exchange -> {
            exchange.getResponseHeaders().set("Location", origin + "/redirected");
            reply(exchange, status, "application/json", "private-source-and-engine-message");
        };
        assertFailure(client(Duration.ofSeconds(2)), Reason.HTTP_ERROR);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{}", "null", "[]", "{\"token\":null}", "{\"token\":123}", "{\"token\":\"\"}",
            "{\"token\":\"private-source-and-engine-message\"}",
            "{\"token\":\"../../escape\"}", "not-json-private-source-and-engine-message",
            "{\"token\":\"d85cd024-1548-4165-96c7-7bc88673f194\"} {}",
            "{\"token\":\"d85cd024-1548-4165-96c7-7bc88673f194\",\"token\":\"duplicate\"}",
            "{\"token\":\"d85cd024-1548-4165-96c7-7bc88673f194\",\"error\":\"private-source-and-engine-message\"}"
    })
    void rejectsMalformedTokenResponses(String body) {
        response = exchange -> reply(exchange, 201, "application/json", body);
        assertFailure(client(Duration.ofSeconds(2)), Reason.INVALID_RESPONSE);
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsOversizedChunkedResponse() {
        response = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, 0);
            exchange.getResponseBody().write((TOKEN_JSON + " ".repeat(4096)).getBytes(StandardCharsets.UTF_8));
        };
        assertFailure(client(Duration.ofSeconds(2)), Reason.INVALID_RESPONSE);
    }

    @Test
    void rejectsInvalidUtf8() {
        response = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, 2);
            exchange.getResponseBody().write(new byte[] {(byte) 0xc3, 0x28});
        };
        assertFailure(client(Duration.ofSeconds(2)), Reason.INVALID_RESPONSE);
    }

    @Test
    void rejectsNonJsonResponse() {
        response = exchange -> reply(exchange, 201, "text/html", TOKEN_JSON);
        assertFailure(client(Duration.ofSeconds(2)), Reason.INVALID_RESPONSE);
    }

    @Test
    void rejectsCompressedResponse() {
        response = exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            reply(exchange, 201, "application/json", TOKEN_JSON);
        };
        assertFailure(client(Duration.ofSeconds(2)), Reason.INVALID_RESPONSE);
    }

    @Test
    void reportsUnavailableServer() {
        server.stop(0);
        assertFailure(client(Duration.ofSeconds(2)), Reason.UNAVAILABLE);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsWaitingBeforeHeadersAndDuringResponseBody(boolean sendHeaders) {
        var release = new CountDownLatch(1);
        response = exchange -> {
            if (sendHeaders) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            awaitRelease(release);
        };
        long start = System.nanoTime();
        try {
            assertFailure(client(Duration.ofMillis(300)), Reason.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(requests).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void restoresInterruptFlagWithoutRetryingPost() throws Exception {
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interruptPreserved = new AtomicReference<Boolean>();
        response = exchange -> awaitRelease(release);
        var caller = new Thread(() -> {
            try {
                client(Duration.ofSeconds(2)).submitJava(submission());
            } catch (Throwable exception) {
                failure.set(exception);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            caller.start();
            captured.get(2, TimeUnit.SECONDS);
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(Judge0ClientException.class,
                    exception -> assertThat(exception.reason()).isEqualTo(Reason.INTERRUPTED));
            assertThat(interruptPreserved.get()).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(3000);
        }
    }

    private HttpJudge0Client client(Duration timeout) {
        return new HttpJudge0Client(http, properties(timeout));
    }

    private Judge0Properties properties(Duration timeout) {
        return new Judge0Properties(origin, 1001, Duration.ofMillis(100), timeout,
                Duration.ofMillis(50), Duration.ofSeconds(5));
    }

    private Judge0Submission submission() {
        return new Judge0Submission("class Main {}", "", new ExecutionLimits(2000, 5000, 128000000));
    }

    private void assertFailure(Judge0Client client, Reason reason) {
        assertThatThrownBy(() -> client.submitJava(submission()))
                .isInstanceOfSatisfying(Judge0ClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(reason);
                    assertThat(exception.getMessage()).contains("unconfirmed")
                            .doesNotContain("private-source", "engine-message", "class Main", origin.toString());
                    assertThat(exception.getCause()).isNull();
                });
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record CapturedRequest(String method, URI uri, String contentType, String accept, byte[] body) {
    }
}
