package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0Result;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteReport;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real HTTP, validation, coordinator and evaluator. Only the engine client is controlled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runner.internal-key=synthetic-concurrency-key-123456789012345")
@Import(RunEndpointConcurrencyTests.EngineConfiguration.class)
class RunEndpointConcurrencyTests {

    private static final String KEY = "synthetic-concurrency-key-123456789012345";
    private static final Judge0SubmissionToken TOKEN =
            new Judge0SubmissionToken("11111111-2222-3333-4444-555555555555");
    private final JsonMapper mapper = JsonMapper.builder().build();
    @LocalServerPort
    private int port;
    @Autowired
    @Qualifier("controlledJudge0")
    private Judge0Client engine;
    private String body;

    @BeforeEach
    void prepare() throws Exception {
        reset(engine);
        body = Files.readString(Path.of("docs/api/examples/run-request.json"));
        when(engine.submitProject(any(byte[].class), any(ExecutionLimits.class), any(Duration.class)))
                .thenReturn(TOKEN);
        when(engine.awaitResult(any(), any())).thenReturn(result(Judge0Status.ACCEPTED));
        when(engine.getSuiteReport(any(), any())).thenReturn(report(true));
    }

    @Test
    void boundsTheRealHttpQueueAndAdmitsNewWorkAfterItDrains() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var running = new AtomicInteger();
        var maximum = new AtomicInteger();
        var calls = new AtomicInteger();
        when(engine.awaitResult(any(), any())).thenAnswer(invocation -> {
            maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown();
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                }
                return result(Judge0Status.ACCEPTED);
            } finally {
                running.decrementAndGet();
            }
        });
        try (var http = HttpClient.newHttpClient()) {
            var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            try {
                requests.add(post(http, "first"));
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                // The first Run is held in the engine. Ten more may wait, the twelfth must fail.
                for (int i = 1; i <= 11; i++) {
                    requests.add(post(http, "queued-" + i));
                }
                var firstCompleted = (HttpResponse<?>) CompletableFuture.anyOf(
                        requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                assertThat(firstCompleted.statusCode()).isEqualTo(429);
                assertThat(firstCompleted.headers().firstValue("Retry-After")).contains("1");
                assertThat(firstCompleted.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(mapper.readTree(firstCompleted.body().toString()).get("code").asString())
                        .isEqualTo("QUEUE_CAPACITY_EXCEEDED");
                assertThat(calls).hasValue(1);
            } finally {
                release.countDown();
            }
            int accepted = 0;
            int rejected = 0;
            var ids = new java.util.HashSet<String>();
            for (var request : requests) {
                var response = request.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 429) {
                    rejected++;
                } else {
                    assertAccepted(response);
                    ids.add(mapper.readValue(response.body(), RunResponse.class).requestId());
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(11);
            assertThat(rejected).isEqualTo(1);
            assertThat(ids).hasSize(11);
            assertAccepted(post(http, "after-overload").get(10, TimeUnit.SECONDS));
            assertThat(maximum).hasValue(1);
            verify(engine, times(12)).submitProject(any(byte[].class), any(ExecutionLimits.class), any(Duration.class));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Judge0Status.class, names = {
            "COMPILATION_ERROR", "RUNTIME_ERROR_NZEC", "TIME_LIMIT_EXCEEDED"
    })
    void ordinarySolutionFailuresReleaseThePipelineForTheNextHttpRun(Judge0Status status) throws Exception {
        when(engine.awaitResult(any(), any())).thenReturn(result(status), result(Judge0Status.ACCEPTED));
        when(engine.getSuiteReport(any(), any())).thenReturn(report(false), report(true));
        Verdict expected = switch (status) {
            case COMPILATION_ERROR -> Verdict.COMPILATION_ERROR;
            case TIME_LIMIT_EXCEEDED -> Verdict.TIME_LIMIT_EXCEEDED;
            default -> Verdict.RUNTIME_ERROR;
        };
        try (var http = HttpClient.newHttpClient()) {
            var response = post(http, "negative").get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            var result = mapper.readValue(response.body(), RunResponse.class).result();
            assertThat(result.status()).isEqualTo(expected);
            assertThat(result.passedTests()).isZero();
            assertThat(result.testResults().getFirst().status()).isEqualTo(TestVerdict.NOT_RUN);
            assertAccepted(post(http, "recovered").get(10, TimeUnit.SECONDS));
            verify(engine, times(2)).submitProject(any(byte[].class), any(ExecutionLimits.class), any(Duration.class));
        }
    }

    private CompletableFuture<HttpResponse<String>> post(HttpClient http, String id) {
        var input = mapper.readTree(body).asObject();
        input.put("requestId", id);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/runs"))
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                .header("X-CodeLobby-Internal-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertAccepted(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        var result = mapper.readValue(response.body(), RunResponse.class).result();
        assertThat(result.status()).isEqualTo(Verdict.ACCEPTED);
        assertThat(result.passedTests()).isEqualTo(1);
        assertThat(result.totalTests()).isEqualTo(1);
    }

    private static Judge0Result result(Judge0Status status) {
        boolean compilation = status == Judge0Status.COMPILATION_ERROR;
        return new Judge0Result(TOKEN, status, null, null, null, null,
                compilation ? null : 1.0, compilation ? null : 2.0,
                compilation ? null : 32_000_000L, compilation ? null : status == Judge0Status.ACCEPTED ? 0 : 1, null);
    }

    private static Judge0SuiteReport report(boolean available) {
        byte[] content = available ? "{\"testId\":\"public-1\",\"status\":\"ACCEPTED\"}\n"
                .getBytes(StandardCharsets.UTF_8) : null;
        return new Judge0SuiteReport(available ? Judge0SuiteReport.State.AVAILABLE : Judge0SuiteReport.State.MISSING,
                content, new Judge0CompilationUsage(1, 2));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EngineConfiguration {
        @Bean
        @Primary
        Judge0Client controlledJudge0() {
            return mock(Judge0Client.class);
        }
    }
}
