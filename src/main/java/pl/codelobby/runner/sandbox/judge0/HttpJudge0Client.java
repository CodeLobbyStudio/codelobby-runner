package pl.codelobby.runner.sandbox.judge0;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static pl.codelobby.runner.sandbox.judge0.Judge0ClientException.Reason;

/** Asynchronous submission creation only, without polling, grading or application-level POST retries. */
public final class HttpJudge0Client implements Judge0Client {

    private static final int MAX_TOKEN_RESPONSE_BYTES = 4096;
    private final HttpClient http;
    private final Judge0Properties properties;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public HttpJudge0Client(HttpClient http, Judge0Properties properties) {
        this.http = Objects.requireNonNull(http, "http");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Judge0SubmissionToken submitJava(Judge0Submission submission) {
        Objects.requireNonNull(submission, "submission");
        var limits = submission.limits();
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "source_code", encode(submission.sourceCode()),
                "stdin", encode(submission.stdin()),
                "language_id", properties.javaLanguageId(),
                "cpu_time_limit", BigDecimal.valueOf(limits.cpuTimeMs(), 3),
                "cpu_extra_time", 0,
                "wall_time_limit", BigDecimal.valueOf(limits.wallTimeMs(), 3),
                // Judge0/Isolate use KiB. Round down so conversion never increases the budget.
                "memory_limit", limits.memoryLimitBytes() / 1024,
                "enable_network", false,
                "number_of_runs", 1));
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve(
                        "/submissions?base64_encoded=true&wait=false"))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        var pending = http.sendAsync(request, info -> new LimitedBodySubscriber());
        HttpResponse<byte[]> response;
        try {
            // Also bound stalled response bodies, not just connection/response-header waiting.
            response = pending.get(properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new Judge0ClientException(Reason.TIMEOUT);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Judge0ClientException(Reason.INTERRUPTED);
        } catch (ExecutionException exception) {
            if (hasCause(exception, HttpTimeoutException.class)) {
                throw new Judge0ClientException(Reason.TIMEOUT);
            }
            if (hasCause(exception, ResponseLimitException.class)) {
                throw new Judge0ClientException(Reason.INVALID_RESPONSE);
            }
            throw new Judge0ClientException(Reason.UNAVAILABLE);
        }
        if (response.statusCode() != 201) {
            throw new Judge0ClientException(Reason.HTTP_ERROR);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")
                || !encoding.equalsIgnoreCase("identity")) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE);
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
            var root = mapper.readTree(json);
            var token = root == null || !root.isObject() ? null : root.get("token");
            if (token == null || !token.isString() || root.has("error")) {
                throw new Judge0ClientException(Reason.INVALID_RESPONSE);
            }
            return new Judge0SubmissionToken(token.asString());
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE);
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static final class ResponseLimitException extends IOException {
        ResponseLimitException() {
            super("Judge0 token response exceeds the size limit");
        }
    }

    /** Enforces the ceiling while receiving bytes, including chunked responses. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean finished;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (finished) {
                return;
            }
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_TOKEN_RESPONSE_BYTES - received) {
                    finished = true;
                    subscription.cancel();
                    delegate.onError(new ResponseLimitException());
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            if (!finished) {
                finished = true;
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!finished) {
                finished = true;
                delegate.onComplete();
            }
        }
    }
}
