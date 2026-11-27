package pl.codelobby.runner.sandbox.judge0;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/** Authenticated bounded transport for the optional local artifact adapter. Not a Spring bean. */
public final class HttpJudge0ArtifactStore implements Judge0ArtifactStore {
    private final HttpJudge0Client transport;
    private final Judge0Properties properties;
    private final String key;

    public HttpJudge0ArtifactStore(HttpClient http, Judge0Properties properties, String key) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER || http.proxy().orElse(null) != HttpClient.Builder.NO_PROXY
                || key == null || !key.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("Private artifact transport requires a safe key and direct nonredirecting HTTP");
        }
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = new HttpJudge0Client(http, properties);
        this.key = key;
    }

    @Override
    public Judge0ArtifactSnapshot fetch(Judge0SubmissionToken token, Duration left) {
        try {
            var response = exchange(token, "GET", left);
            var state = response.get("state");
            if (state == null || !state.isString()) {
                throw invalid();
            }
            if (!state.asString().equals("AVAILABLE")) {
                if (response.size() != 1) {
                    throw invalid();
                }
                throw new Judge0ArtifactException(switch (state.asString()) {
                    case "MISSING" -> Judge0ArtifactException.Reason.MISSING;
                    case "TOO_LARGE" -> Judge0ArtifactException.Reason.TOO_LARGE;
                    case "UNAVAILABLE" -> Judge0ArtifactException.Reason.UNAVAILABLE;
                    default -> Judge0ArtifactException.Reason.INVALID_RESPONSE;
                });
            }
            var encoded = response.get("snapshot");
            var digest = response.get("sha256");
            if (response.size() != 3 || encoded == null || !encoded.isString()
                    || encoded.asString().length() > 2000000 || digest == null || !digest.isString()) {
                throw invalid();
            }
            byte[] content = Base64.getDecoder().decode(encoded.asString());
            if (!Base64.getEncoder().encodeToString(content).equals(encoded.asString())) {
                throw invalid();
            }
            return new Judge0ArtifactSnapshot(content, digest.asString());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    @Override
    public void delete(Judge0SubmissionToken token, Duration left) {
        var response = exchange(token, "DELETE", left);
        if (response.size() != 1 || response.get("state") == null || !response.get("state").isString()
                || !response.get("state").asString().equals("DELETED")) {
            throw invalid();
        }
    }

    @Override
    public void awaitCleanup(Judge0SubmissionToken token, Duration left) {
        if (left == null || left.isZero() || left.isNegative()) {
            throw new IllegalArgumentException("Cleanup confirmation timeout must be positive");
        }
        Duration timeout = left.compareTo(properties.resultTimeout()) < 0 ? left : properties.resultTimeout();
        long started = System.nanoTime();
        long budget = timeout.toNanos();
        while (true) {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new Judge0ArtifactException(Judge0ArtifactException.Reason.UNAVAILABLE);
            }
            var response = exchange(token, "GET", Duration.ofNanos(remaining), true);
            if (response.size() != 1 || response.get("state") == null || !response.get("state").isString()) {
                throw invalid();
            }
            String state = response.get("state").asString();
            if (state.equals("CLEANED_UP")) {
                return;
            }
            if (!state.equals("PENDING")) {
                throw new Judge0ArtifactException(Judge0ArtifactException.Reason.UNAVAILABLE);
            }
            remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new Judge0ArtifactException(Judge0ArtifactException.Reason.UNAVAILABLE);
            }
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining, properties.pollInterval().toNanos()));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new Judge0ArtifactException(Judge0ArtifactException.Reason.INTERRUPTED);
            }
        }
    }

    private JsonNode exchange(Judge0SubmissionToken token, String method, Duration left) {
        return exchange(token, method, left, false);
    }

    private JsonNode exchange(Judge0SubmissionToken token, String method, Duration left, boolean cleanup) {
        Objects.requireNonNull(token, "token");
        if (left == null || left.isZero() || left.isNegative()) {
            throw new IllegalArgumentException("Private artifact timeout must be positive");
        }
        Duration timeout = left.compareTo(properties.requestTimeout()) < 0 ? left : properties.requestTimeout();
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve("/codelobby/v1/java-artifacts/" + token.value() + (cleanup ? "/cleanup" : "")))
                .timeout(timeout).header("X-CodeLobby-Artifact-Key", key).header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try {
            return transport.exchange(request, 200, method.equals("GET") && !cleanup ? 2000512 : 256, token, timeout);
        } catch (Judge0ClientException exception) {
            throw new Judge0ArtifactException(exception.reason() == Judge0ClientException.Reason.INTERRUPTED
                    ? Judge0ArtifactException.Reason.INTERRUPTED : Judge0ArtifactException.Reason.UNAVAILABLE);
        }
    }

    private static Judge0ArtifactException invalid() {
        return new Judge0ArtifactException(Judge0ArtifactException.Reason.INVALID_RESPONSE);
    }
}
