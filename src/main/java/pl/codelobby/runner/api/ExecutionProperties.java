package pl.codelobby.runner.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Trusted deployment settings for waiting capacity. Active execution remains limited to one. */
@ConfigurationProperties(prefix = "runner.execution", ignoreUnknownFields = false)
public record ExecutionProperties(@DefaultValue("10") int maxPending) {

    public ExecutionProperties {
        if (maxPending < 0 || maxPending == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("runner.execution.max-pending must be between 0 and 2147483646");
        }
    }
}
