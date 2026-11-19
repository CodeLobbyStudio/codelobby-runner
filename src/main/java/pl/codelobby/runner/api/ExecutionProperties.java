package pl.codelobby.runner.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Trusted deployment settings for active and waiting capacity. Defaults retain serial execution. */
@ConfigurationProperties(prefix = "runner.execution", ignoreUnknownFields = false)
public record ExecutionProperties(@DefaultValue("1") int maxActive, @DefaultValue("10") int maxPending) {

    public ExecutionProperties {
        if (maxActive < 1 || maxActive > 2 || maxPending < 0
                || (long) maxActive + maxPending > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("runner.execution requires 1 or 2 active operations and a nonnegative pending limit without total overflow");
        }
    }
}
