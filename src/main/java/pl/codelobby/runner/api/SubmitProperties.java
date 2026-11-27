package pl.codelobby.runner.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Submit is opt-in and requires the optional adapter's private artifact key. */
@ConfigurationProperties(prefix = "runner.submit", ignoreUnknownFields = false)
public record SubmitProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String artifactKey) {
    public SubmitProperties {
        if (enabled && (artifactKey == null || !artifactKey.matches("[A-Za-z0-9_-]{32,256}"))) {
            throw new IllegalArgumentException("Enabled Submit requires a valid private artifact key");
        }
    }

    @Override
    public String toString() {
        return "SubmitProperties[enabled=" + enabled + ", artifactKey=<redacted>]";
    }
}
