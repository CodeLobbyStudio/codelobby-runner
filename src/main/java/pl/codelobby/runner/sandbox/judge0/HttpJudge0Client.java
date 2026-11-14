package pl.codelobby.runner.sandbox.judge0;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import pl.codelobby.runner.model.ExecutionLimits;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static pl.codelobby.runner.sandbox.judge0.Judge0ClientException.Reason;

/** Bounded engine communication and polling, without grading or application-level POST retries. */
public final class HttpJudge0Client implements Judge0Client {

    private static final int MULTI_FILE_LANGUAGE_ID = 89;
    private static final int MAX_ARCHIVE_BYTES = 2_000_000;
    private static final int MAX_TOKEN_RESPONSE_BYTES = 4096;
    private static final int MAX_RESULT_RESPONSE_BYTES = 300000;
    private static final String RESULT_FIELDS = "token,status,stdout,stderr,compile_output,message,"
            + "time,wall_time,memory,exit_code,exit_signal";
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
        var fields = executionFields(properties.javaLanguageId(), submission.limits());
        fields.put("source_code", encode(submission.sourceCode()));
        fields.put("stdin", encode(submission.stdin()));
        return submit(fields, properties.requestTimeout());
    }

    @Override
    public Judge0SubmissionToken submitProject(byte[] archive, ExecutionLimits limits) {
        return submitProject(archive, limits, properties.requestTimeout());
    }

    @Override
    public Judge0SubmissionToken submitProject(byte[] archive, ExecutionLimits limits, Duration timeout) {
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Invalid Judge0 project archive size");
        }
        Judge0Submission.validateLimits(limits);
        var fields = executionFields(MULTI_FILE_LANGUAGE_ID, limits);
        fields.put("additional_files", Base64.getEncoder().encodeToString(archive));
        return submit(fields, boundedTimeout(timeout, properties.requestTimeout()));
    }

    private static Map<String, Object> executionFields(int languageId, ExecutionLimits limits) {
        return new HashMap<>(Map.of(
                "language_id", languageId,
                "cpu_time_limit", BigDecimal.valueOf(limits.cpuTimeMs(), 3),
                "cpu_extra_time", 0,
                "wall_time_limit", BigDecimal.valueOf(limits.wallTimeMs(), 3),
                // Judge0/Isolate use KiB. Round down so conversion never increases the budget.
                "memory_limit", limits.memoryLimitBytes() / 1024,
                "enable_network", false,
                "number_of_runs", 1));
    }

    private Judge0SubmissionToken submit(Map<String, Object> fields, Duration timeout) {
        byte[] payload = mapper.writeValueAsBytes(fields);
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve(
                        "/submissions?base64_encoded=true&wait=false"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        var root = exchange(request, 201, MAX_TOKEN_RESPONSE_BYTES, null, timeout);
        try {
            var token = root.get("token");
            if (token == null || !token.isString()) {
                throw new IllegalArgumentException("Missing token");
            }
            return new Judge0SubmissionToken(token.asString());
        } catch (IllegalArgumentException exception) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE);
        }
    }

    @Override
    public Judge0Result getSubmission(Judge0SubmissionToken token) {
        return getSubmission(token, properties.requestTimeout());
    }

    @Override
    public Judge0SuiteReport getSuiteReport(Judge0SubmissionToken token) {
        return getSuiteReport(token, properties.requestTimeout());
    }

    @Override
    public Judge0SuiteReport getSuiteReport(Judge0SubmissionToken token, Duration timeout) {
        Objects.requireNonNull(token, "token");
        timeout = boundedTimeout(timeout, properties.requestTimeout());
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve("/codelobby/v1/suite-reports/" + token.value()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET().build();
        int maxReportBytes = Judge0SuiteReport.MAX_BYTES;
        int maxEncodedBytes = 4 * ((maxReportBytes + 2) / 3);
        var root = exchange(request, 200, maxEncodedBytes + 512, token, timeout);
        try {
            var stateNode = root.get("state");
            if (stateNode == null || !stateNode.isString()) {
                throw new IllegalArgumentException("Missing suite report state");
            }
            var state = Judge0SuiteReport.State.valueOf(stateNode.asString());
            var configuration = root.get("configurationError");
            if (configuration != null && !configuration.isBoolean()) {
                throw new IllegalArgumentException("Invalid configuration outcome");
            }
            boolean configurationError = configuration != null && configuration.asBoolean();
            var build = root.get("compilation");
            Judge0CompilationUsage compilation = null;
            if (build != null && !build.isNull()) {
                if (!build.isObject() || !build.has("cpuTimeMs") || !build.get("cpuTimeMs").isNumber()
                        || !build.has("wallTimeMs") || !build.get("wallTimeMs").isNumber()) {
                    throw new IllegalArgumentException("Invalid compilation measurements");
                }
                compilation = new Judge0CompilationUsage(
                        build.get("cpuTimeMs").asDouble(), build.get("wallTimeMs").asDouble());
            }
            if (state != Judge0SuiteReport.State.AVAILABLE) {
                if (root.has("report")) {
                    throw new IllegalArgumentException("Unexpected suite report");
                }
                return new Judge0SuiteReport(state, null, compilation, configurationError);
            }
            var report = root.get("report");
            if (report == null || !report.isString() || report.asString().length() > maxEncodedBytes) {
                throw new IllegalArgumentException("Invalid suite report");
            }
            return new Judge0SuiteReport(state, Base64.getDecoder().decode(report.asString()),
                    compilation, configurationError);
        } catch (IllegalArgumentException exception) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE, token);
        }
    }

    @Override
    public Judge0Result awaitResult(Judge0SubmissionToken token) {
        return awaitResult(token, properties.resultTimeout());
    }

    @Override
    public Judge0Result awaitResult(Judge0SubmissionToken token, Duration timeout) {
        Objects.requireNonNull(token, "token");
        long started = System.nanoTime();
        long budget = nanos(boundedTimeout(timeout, properties.resultTimeout()));
        while (true) {
            long remaining = remaining(started, budget, token);
            var queryTimeout = Duration.ofNanos(Math.min(nanos(properties.requestTimeout()), remaining));
            var result = getSubmission(token, queryTimeout);
            remaining = remaining(started, budget, token);
            if (result.status().finished()) {
                return result;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(nanos(properties.pollInterval()), remaining));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new Judge0ClientException(Reason.INTERRUPTED, token);
            }
        }
    }

    private Judge0Result getSubmission(Judge0SubmissionToken token, Duration timeout) {
        Objects.requireNonNull(token, "token");
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve("/submissions/" + token.value()
                        + "?base64_encoded=true&fields=" + RESULT_FIELDS))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET().build();
        var root = exchange(request, 200, MAX_RESULT_RESPONSE_BYTES, token, timeout);
        try {
            var returnedToken = root.get("token");
            if (returnedToken == null || !returnedToken.isString() || !returnedToken.asString().equals(token.value())) {
                throw new IllegalArgumentException("Mismatched token");
            }
            var statusNode = root.get("status");
            if (statusNode == null || !statusNode.isObject()) {
                throw new IllegalArgumentException("Missing status");
            }
            Integer id = integer(statusNode.get("id"));
            if (id == null) {
                throw new IllegalArgumentException("Missing status ID");
            }
            var status = Judge0Status.fromId(id);
            if (status == Judge0Status.INTERNAL_ERROR) {
                throw new Judge0ClientException(Reason.ENGINE_ERROR, token);
            }
            return new Judge0Result(token, status,
                    decode(root.get("stdout"), 65536), decode(root.get("stderr"), 65536),
                    decode(root.get("compile_output"), 65536), decode(root.get("message"), 4096),
                    milliseconds(root.get("time")), milliseconds(root.get("wall_time")),
                    memoryBytes(root.get("memory")), integer(root.get("exit_code")), integer(root.get("exit_signal")));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE, token);
        }
    }

    private JsonNode exchange(HttpRequest request, int expectedStatus, int maxBytes,
            Judge0SubmissionToken token, Duration timeout) {
        var pending = http.sendAsync(request, info -> new LimitedBodySubscriber(maxBytes));
        HttpResponse<byte[]> response;
        try {
            // Also bound stalled response bodies, not just connection/response-header waiting.
            response = pending.get(nanos(timeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new Judge0ClientException(Reason.TIMEOUT, token);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Judge0ClientException(Reason.INTERRUPTED, token);
        } catch (ExecutionException exception) {
            if (hasCause(exception, HttpTimeoutException.class)) {
                throw new Judge0ClientException(Reason.TIMEOUT, token);
            }
            if (hasCause(exception, ResponseLimitException.class)) {
                throw new Judge0ClientException(Reason.INVALID_RESPONSE, token);
            }
            throw new Judge0ClientException(Reason.UNAVAILABLE, token);
        }
        if (response.statusCode() != expectedStatus) {
            throw new Judge0ClientException(Reason.HTTP_ERROR, token);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")
                || !encoding.equalsIgnoreCase("identity")) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE, token);
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
            var root = mapper.readTree(json);
            if (root == null || !root.isObject() || root.has("error")) {
                throw new Judge0ClientException(Reason.INVALID_RESPONSE, token);
            }
            return root;
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            throw new Judge0ClientException(Reason.INVALID_RESPONSE, token);
        }
    }

    private static Duration boundedTimeout(Duration requested, Duration configured) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("Judge0 timeout must be positive");
        }
        return requested.compareTo(configured) < 0 ? requested : configured;
    }

    private static long remaining(long started, long budget, Judge0SubmissionToken token) {
        long remaining = budget - (System.nanoTime() - started);
        if (remaining <= 0) {
            throw new Judge0ClientException(Reason.TIMEOUT, token);
        }
        return remaining;
    }

    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String decode(JsonNode node, int maxBytes) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("Invalid encoded diagnostic");
        }
        String encoded = node.asString();
        int maxEncoded = 4 * ((maxBytes + 2) / 3);
        // Judge0's Ruby Base64 encoder wraps at 60 columns and adds a trailing newline.
        // Permit LF/CRLF wrapping only, not arbitrary characters accepted by a MIME decoder.
        int maxWrapped = maxEncoded + 2 * ((maxEncoded + 59) / 60);
        if (encoded.length() > maxWrapped) {
            throw new IllegalArgumentException("Invalid encoded diagnostic");
        }
        encoded = encoded.replace("\r", "").replace("\n", "");
        if (encoded.length() > maxEncoded) {
            throw new IllegalArgumentException("Invalid encoded diagnostic");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Diagnostic exceeds size limit");
        }
        // Program output may be arbitrary bytes. Replace malformed UTF-8 instead of failing communication.
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Double milliseconds(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() && !node.isString()) {
            throw new IllegalArgumentException("Invalid time measurement");
        }
        String value = node.isString() ? node.asString() : node.toString();
        if (value.length() > 32 || !value.matches("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("Invalid time measurement");
        }
        double millis = Double.parseDouble(value) * 1000;
        if (!Double.isFinite(millis)) {
            throw new IllegalArgumentException("Invalid time measurement");
        }
        return millis;
    }

    private static Long memoryBytes(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Invalid memory measurement");
        }
        long kib = new BigDecimal(node.toString()).longValueExact();
        if (kib < 0) {
            throw new IllegalArgumentException("Invalid memory measurement");
        }
        return Math.multiplyExact(kib, 1024);
    }

    private static Integer integer(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid integer value");
        }
        return new BigDecimal(node.toString()).intValueExact();
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
            super("Judge0 response exceeds the size limit");
        }
    }

    /** Enforces the ceiling while receiving bytes, including chunked responses. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maxBytes;
        private Flow.Subscription subscription;
        private int received;
        private boolean finished;

        LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

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
                if (buffer.remaining() > maxBytes - received) {
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
