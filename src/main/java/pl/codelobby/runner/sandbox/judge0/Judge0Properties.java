package pl.codelobby.runner.sandbox.judge0;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Trusted deployment settings, independent of participant execution budgets. */
@ConfigurationProperties(prefix = "runner.judge0", ignoreUnknownFields = false)
public record Judge0Properties(
        @DefaultValue("http://127.0.0.1:2359") URI baseUrl,
        @DefaultValue("1001") int javaLanguageId,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("60s") Duration resultTimeout) {

    public Judge0Properties {
        if (baseUrl == null || !("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || (baseUrl.getRawPath() != null && !baseUrl.getRawPath().isEmpty()
                    && !baseUrl.getRawPath().equals("/"))
                || baseUrl.getPort() == 0 || baseUrl.getPort() > 65535) {
            throw new IllegalArgumentException("Judge0 base URL must be an HTTP(S) origin without credentials");
        }
        // Store one form so endpoint paths can be appended without a double slash.
        if (baseUrl.toString().endsWith("/")) {
            baseUrl = URI.create(baseUrl.toString().substring(0, baseUrl.toString().length() - 1));
        }
        if (javaLanguageId < 1) {
            throw new IllegalArgumentException("Judge0 Java language ID must be positive");
        }
        requirePositiveMillis(connectTimeout, "connect-timeout");
        requirePositiveMillis(requestTimeout, "request-timeout");
        requirePositiveMillis(pollInterval, "poll-interval");
        requirePositiveMillis(resultTimeout, "result-timeout");
        if (connectTimeout.compareTo(requestTimeout) > 0
                || requestTimeout.compareTo(resultTimeout) > 0
                || pollInterval.compareTo(resultTimeout) >= 0) {
            throw new IllegalArgumentException("Judge0 timeout settings are inconsistent");
        }
    }

    private static void requirePositiveMillis(Duration value, String name) {
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Judge0 " + name + " must be at least 1ms");
        }
    }
}
