package pl.codelobby.runner.sandbox.judge0;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static pl.codelobby.runner.sandbox.judge0.Judge0ClientException.Reason;

class HttpJudge0PollingTests {

    private static final Judge0SubmissionToken TOKEN = new Judge0SubmissionToken(
            "d85cd024-1548-4165-96c7-7bc88673f194");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Long> queryTimes = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> firstQuery = new CompletableFuture<>();
    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpClient http;
    private URI origin;
    private HttpHandler response;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        response = exchange -> reply(exchange, 200, result(3).toString());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            queryTimes.add(System.nanoTime());
            requestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            firstQuery.complete(null);
            try {
                response.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        http = new Judge0Configuration().judge0HttpClient(properties(Duration.ofSeconds(2), Duration.ofMillis(40)));
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop(0);
        serverThreads.shutdownNow();
        assertThat(serverThreads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        http.close();
    }

    @Test
    void readsEncodedDiagnosticsExitInformationAndConvertedMeasurements() {
        var body = result(3);
        body.put("stdout", encode("5\nżółw"));
        body.put("stderr", encode(""));
        body.put("compile_output", encode("compiler diagnostic"));
        body.put("message", encode("engine message"));
        body.put("time", "0.125");
        body.put("wall_time", 0.250);
        body.put("memory", 125000);
        body.put("exit_code", 0);
        body.put("exit_signal", 0);
        response = exchange -> reply(exchange, 200, body.toString());

        var value = client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN);

        assertThat(value.token()).isEqualTo(TOKEN);
        assertThat(value.status()).isEqualTo(Judge0Status.ACCEPTED);
        assertThat(value.stdout()).isEqualTo("5\nżółw");
        assertThat(value.stderr()).isEmpty();
        assertThat(value.compileOutput()).isEqualTo("compiler diagnostic");
        assertThat(value.message()).isEqualTo("engine message");
        assertThat(value.cpuTimeMs()).isEqualTo(125.0);
        assertThat(value.wallTimeMs()).isEqualTo(250.0);
        assertThat(value.memoryBytes()).isEqualTo(128000000L);
        assertThat(value.exitCode()).isZero();
        assertThat(value.exitSignal()).isZero();
        assertThat(requests).hasValue(1);
        assertThat(requestLines).containsExactly("GET /submissions/" + TOKEN.value()
                + "?base64_encoded=true&fields=token,status,stdout,stderr,compile_output,message,"
                + "time,wall_time,memory,exit_code,exit_signal");
        assertThat(value.toString()).doesNotContain("żółw", "compiler diagnostic", "engine message", TOKEN.value());
    }

    @Test
    void pollsQueueAndProcessingUntilFinishedWithConfiguredPauses() {
        response = exchange -> reply(exchange, 200, result(switch (requests.get()) {
            case 1 -> 1;
            case 2 -> 2;
            default -> 3;
        }).toString());

        var value = client(Duration.ofSeconds(2), Duration.ofMillis(40)).awaitResult(TOKEN);

        assertThat(value.status()).isEqualTo(Judge0Status.ACCEPTED);
        assertThat(requests).hasValue(3);
        for (int index = 1; index < queryTimes.size(); index++) {
            assertThat(queryTimes.get(index) - queryTimes.get(index - 1))
                    .isGreaterThanOrEqualTo(Duration.ofMillis(35).toNanos());
        }
        assertThat(requestLines).allMatch(line -> line.startsWith("GET "));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void singleQueryCanReturnPendingStateAndUnavailableMeasurements(int id) {
        response = exchange -> reply(exchange, 200, result(id).toString());
        var value = client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN);
        assertThat(value.status().finished()).isFalse();
        assertThat(value.stdout()).isNull();
        assertThat(value.cpuTimeMs()).isNull();
        assertThat(value.memoryBytes()).isNull();
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14})
    void stopsOnEveryKnownTerminalProgramStatus(int id) {
        response = exchange -> reply(exchange, 200, result(id).toString());
        var value = client(Duration.ofSeconds(2), Duration.ofMillis(40)).awaitResult(TOKEN);
        assertThat(value.status().id()).isEqualTo(id);
        assertThat(value.status().finished()).isTrue();
        assertThat(requests).hasValue(1);
    }

    @Test
    void treatsJudge0InternalErrorAsInfrastructureFailure() {
        var body = result(13);
        body.put("message", encode("private-engine-secret"));
        response = exchange -> reply(exchange, 200, body.toString());
        assertFailure(Reason.ENGINE_ERROR, () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).awaitResult(TOKEN));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 404, 429, 500, 503})
    void retainsTokenOnHttpErrorWithoutResubmittingOrRetrying(int status) {
        response = exchange -> {
            exchange.getResponseHeaders().set("Location", origin + "/redirect");
            reply(exchange, status, "private-engine-secret");
        };
        assertFailure(Reason.HTTP_ERROR, () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).awaitResult(TOKEN));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{broken-private-engine-secret}",
            "{\"token\":\"d85cd024-1548-4165-96c7-7bc88673f194\",\"status\":{\"id\":3}} {}",
            "{\"token\":\"d85cd024-1548-4165-96c7-7bc88673f194\",\"status\":{\"id\":3,\"id\":2}}"
    })
    void rejectsMalformedResultBodies(String body) {
        response = exchange -> reply(exchange, 200, body);
        assertFailure(Reason.INVALID_RESPONSE,
                () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"token\":null", "\"token\":123", "\"token\":\"00000000-0000-0000-0000-000000000000\"",
            "\"status\":null", "\"status\":{\"id\":0}", "\"status\":{\"id\":15}",
            "\"status\":{\"id\":\"3\"}", "\"status\":{\"id\":3.0}",
            "\"stdout\":123", "\"stderr\":\"%%%\"", "\"compile_output\":\"%%%\"", "\"message\":false",
            "\"time\":\"NaN\"", "\"time\":-1", "\"time\":true", "\"wall_time\":\"1e309\"",
            "\"memory\":-1", "\"memory\":0.5", "\"memory\":9223372036854775807", "\"memory\":\"100\"",
            "\"exit_code\":0.1", "\"exit_code\":2147483648", "\"exit_signal\":-1"
    })
    void rejectsInvalidResultFields(String entry) {
        var body = result(3);
        var replacement = mapper.readTree("{" + entry + "}");
        var property = replacement.propertyNames().iterator().next();
        body.set(property, replacement.get(property));
        response = exchange -> reply(exchange, 200, body.toString());
        assertFailure(Reason.INVALID_RESPONSE,
                () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN));
    }

    @Test
    void preservesConfirmedMemoryMessageAndReplacesNonUtf8ProgramOutput() {
        var body = result(12);
        body.put("message", encode("Memory limit exceeded"));
        body.put("stdout", Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff}));
        response = exchange -> reply(exchange, 200, body.toString());
        var value = client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN);
        assertThat(value.message()).isEqualTo("Memory limit exceeded");
        assertThat(value.stdout()).isEqualTo("\uFFFD");
        assertThat(value.status()).isEqualTo(Judge0Status.RUNTIME_ERROR_OTHER);
    }

    @Test
    void acceptsMaximumDiagnosticSizeButRejectsExcess() {
        var body = result(3);
        body.put("stdout", encode("a".repeat(65536)));
        response = exchange -> reply(exchange, 200, body.toString());
        assertThat(client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN).stdout()).hasSize(65536);
        body.put("stdout", encode("a".repeat(65537)));
        assertFailure(Reason.INVALID_RESPONSE,
                () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void acceptsRubyStyleBase64LineWrappingIncludingTrailingNewline(String newline) {
        String output = "ż".repeat(32768);
        var encoder = Base64.getMimeEncoder(60, newline.getBytes(StandardCharsets.US_ASCII));
        String wrapped = encoder.encodeToString(output.getBytes(StandardCharsets.UTF_8)) + newline;
        String message = encoder.encodeToString("Memory limit exceeded".getBytes(StandardCharsets.UTF_8)) + newline;
        var body = result(12);
        body.put("stdout", wrapped);
        body.put("stderr", wrapped);
        body.put("compile_output", wrapped);
        body.put("message", message);
        response = exchange -> reply(exchange, 200, body.toString());

        var value = client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN);

        assertThat(value.stdout()).isEqualTo(output);
        assertThat(value.stderr()).isEqualTo(output);
        assertThat(value.compileOutput()).isEqualTo(output);
        assertThat(value.message()).isEqualTo("Memory limit exceeded");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TQ%==\n", "TQ==\t", "TQ== ", "TQ==\nprivate-engine-secret"})
    void stillRejectsBase64CharactersOtherThanPermittedLineEndings(String encoded) {
        var body = result(3);
        body.put("stdout", encoded);
        response = exchange -> reply(exchange, 200, body.toString());
        assertFailure(Reason.INVALID_RESPONSE,
                () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN));
    }

    @Test
    void rejectsOversizedChunkedResultBeforeParsing() {
        response = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(" ".repeat(300001).getBytes(StandardCharsets.UTF_8));
        };
        assertFailure(Reason.INVALID_RESPONSE,
                () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN));
    }

    @Test
    void reportsUnavailableServerAndRetainsKnownToken() {
        server.stop(0);
        assertFailure(Reason.UNAVAILABLE,
                () -> client(Duration.ofSeconds(2), Duration.ofMillis(40)).getSubmission(TOKEN));
    }

    @Test
    void boundsTotalPollingTimeEvenWhenEveryResponseIsStillQueued() {
        response = exchange -> reply(exchange, 200, result(1).toString());
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertFailure(Reason.TIMEOUT,
                        () -> client(Duration.ofMillis(400), Duration.ofMillis(50)).awaitResult(TOKEN)));
        assertThat(requests.get()).isGreaterThan(1).isLessThan(10);
        assertThat(requestLines).allMatch(line -> line.startsWith("GET "));
    }

    @Test
    void doesNotSleepAnExtraPollIntervalAfterOverallDeadline() {
        response = exchange -> reply(exchange, 200, result(2).toString());
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertFailure(Reason.TIMEOUT,
                        () -> client(Duration.ofMillis(400), Duration.ofMillis(300)).awaitResult(TOKEN)));
        assertThat(requests.get()).isBetween(1, 2);
    }

    @Test
    void boundsAStalledResponseByRemainingOverallTime() {
        var release = new CountDownLatch(1);
        response = exchange -> {
            if (requests.get() == 1) {
                reply(exchange, 200, result(1).toString());
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                awaitRelease(release);
            }
        };
        try {
            long started = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertFailure(Reason.TIMEOUT,
                            () -> client(Duration.ofMillis(600), Duration.ofMillis(300)).awaitResult(TOKEN)));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(850));
            assertThat(requests).hasValue(2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void interruptDuringPollingPreservesFlagAndKnownToken() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var flag = new AtomicReference<Boolean>();
        response = exchange -> reply(exchange, 200, result(1).toString());
        var caller = new Thread(() -> {
            try {
                client(Duration.ofSeconds(2), Duration.ofSeconds(1)).awaitResult(TOKEN);
            } catch (Throwable exception) {
                failure.set(exception);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            caller.start();
            firstQuery.get(2, TimeUnit.SECONDS);
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(Judge0ClientException.class, exception -> {
                assertThat(exception.reason()).isEqualTo(Reason.INTERRUPTED);
                assertThat(exception.submissionToken()).isEqualTo(TOKEN);
            });
            assertThat(flag.get()).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            caller.interrupt();
            caller.join(3000);
        }
    }

    private HttpJudge0Client client(Duration overall, Duration interval) {
        return new HttpJudge0Client(http, properties(overall, interval));
    }

    private Judge0Properties properties(Duration overall, Duration interval) {
        return new Judge0Properties(origin, 1001, Duration.ofMillis(100), overall, interval, overall);
    }

    private ObjectNode result(int id) {
        var root = mapper.createObjectNode();
        root.put("token", TOKEN.value());
        root.putObject("status").put("id", id).put("description", "untrusted description");
        return root;
    }

    private void assertFailure(Reason reason, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(Judge0ClientException.class, exception -> {
            assertThat(exception.reason()).isEqualTo(reason);
            assertThat(exception.submissionToken()).isEqualTo(TOKEN);
            assertThat(exception.getMessage()).contains("unconfirmed")
                    .doesNotContain(TOKEN.value(), "private-engine-secret", origin.toString());
            assertThat(exception.getCause()).isNull();
        });
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
