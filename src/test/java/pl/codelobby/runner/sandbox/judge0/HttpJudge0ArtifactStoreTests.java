package pl.codelobby.runner.sandbox.judge0;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpJudge0ArtifactStoreTests {
    private static final String KEY = "dummy-key-" + "x".repeat(40);
    private final Judge0SubmissionToken token = new Judge0SubmissionToken("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private HttpClient http;
    private HttpJudge0ArtifactStore store;
    private String body;
    private int status = 200;
    private String method;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        body = "{\"state\":\"AVAILABLE\",\"snapshot\":\"" + Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))
                + "\",\"sha256\":\"" + "a".repeat(64) + "\"}";
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            method = exchange.getRequestMethod();
            assertThat(exchange.getRequestHeaders().getFirst("X-CodeLobby-Artifact-Key")).isEqualTo(KEY);
            assertThat(exchange.getRequestURI().getPath()).isIn("/codelobby/v1/java-artifacts/" + token.value(),
                    "/codelobby/v1/java-artifacts/" + token.value() + "/cleanup");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
        var properties = new Judge0Properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 1001,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(5));
        store = new HttpJudge0ArtifactStore(http, properties, KEY);
    }

    @AfterEach
    void stop() {
        http.close();
        server.stop(0);
    }

    @Test
    void fetchesAndDeletesThroughAuthenticatedRequests() {
        var snapshot = store.fetch(token, Duration.ofSeconds(1));
        assertThat(snapshot.content()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        snapshot.content()[0] = 'x';
        assertThat(snapshot.content()[0]).isEqualTo((byte) '{');
        assertThat(snapshot.toString()).doesNotContain("aaaa");
        assertThat(method).isEqualTo("GET");
        body = "{\"state\":\"DELETED\"}";
        store.delete(token, Duration.ofSeconds(1));
        assertThat(method).isEqualTo("DELETE");
        assertThat(calls.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "TOO_LARGE", "UNAVAILABLE"})
    void reportsKnownUnavailableStates(String state) {
        body = "{\"state\":\"" + state + "\"}";
        assertThatThrownBy(() -> store.fetch(token, Duration.ofSeconds(1)))
                .isInstanceOf(Judge0ArtifactException.class).hasMessageNotContaining(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"state\":\"UNKNOWN\"}", "{\"state\":\"AVAILABLE\",\"snapshot\":\"bad\"}",
            "{\"state\":\"MISSING\",\"private\":\"secret\"}", "{\"state\":\"MISSING\",\"state\":\"MISSING\"}",
            "{\"state\":\"MISSING\"}{}"})
    void rejectsMalformedResponsesWithoutEchoingThem(String value) {
        body = value;
        assertThatThrownBy(() -> store.fetch(token, Duration.ofSeconds(1)))
                .isInstanceOf(Judge0ArtifactException.class).hasMessageNotContaining("secret").hasMessageNotContaining(KEY);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 401, 403, 404, 503})
    void doesNotFollowRedirectsOrRetryFailures(int responseStatus) {
        status = responseStatus;
        assertThatThrownBy(() -> store.fetch(token, Duration.ofSeconds(1))).isInstanceOf(Judge0ArtifactException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void requiresExplicitCleanupConfirmation() {
        body = "{\"state\":\"CLEANED_UP\"}";
        store.awaitCleanup(token, Duration.ofSeconds(1));
        assertThat(method).isEqualTo("GET");
        body = "{\"state\":\"PENDING\"}";
        assertThatThrownBy(() -> store.awaitCleanup(token, Duration.ofMillis(40)))
                .isInstanceOf(Judge0ArtifactException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"state\":\"AVAILABLE\"}", "{\"state\":\"CLEANED_UP\",\"extra\":true}", "{}"})
    void rejectsAmbiguousCleanupResponses(String value) {
        body = value;
        assertThatThrownBy(() -> store.awaitCleanup(token, Duration.ofSeconds(1)))
                .isInstanceOf(Judge0ArtifactException.class);
    }

    @Test
    void rejectsOversizedBodies() {
        body = "{\"state\":\"AVAILABLE\",\"snapshot\":\"" + "a".repeat(2000513) + "\"}";
        assertThatThrownBy(() -> store.fetch(token, Duration.ofSeconds(1))).isInstanceOf(Judge0ArtifactException.class);
    }
}
