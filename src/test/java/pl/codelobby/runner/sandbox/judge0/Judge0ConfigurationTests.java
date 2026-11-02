package pl.codelobby.runner.sandbox.judge0;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class Judge0ConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Judge0Configuration.class);

    @Test
    void startsWithoutJudge0AndProvidesOneReusableHttpClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Judge0Properties.class).hasSingleBean(HttpClient.class);
            var properties = context.getBean(Judge0Properties.class);
            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:2359"));
            assertThat(properties.javaLanguageId()).isEqualTo(1001);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.resultTimeout()).isEqualTo(Duration.ofSeconds(60));
            var client = context.getBean("judge0HttpClient", HttpClient.class);
            assertThat(client).isSameAs(context.getBean("judge0HttpClient"));
            assertThat(client.connectTimeout()).contains(properties.connectTimeout());
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
            assertThat(client.proxy().orElseThrow()).isSameAs(HttpClient.Builder.NO_PROXY);
        });
    }

    @Test
    void bindsDeploymentOverridesAndNormalizesTrailingSlash() {
        contextRunner.withPropertyValues(
                "runner.judge0.base-url=https://judge0.example.org:8443/",
                "runner.judge0.java-language-id=91",
                "runner.judge0.connect-timeout=1s",
                "runner.judge0.request-timeout=3s",
                "runner.judge0.poll-interval=250ms",
                "runner.judge0.result-timeout=20s").run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(Judge0Properties.class);
                    assertThat(properties.baseUrl()).isEqualTo(URI.create("https://judge0.example.org:8443"));
                    assertThat(properties.javaLanguageId()).isEqualTo(91);
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(properties.resultTimeout()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(context.getBean(HttpClient.class).connectTimeout()).contains(Duration.ofSeconds(1));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/relative", "ftp://localhost:2359", "http:///missing-host", "http://user:password@localhost:2359",
            "http://localhost:2359/api", "http://localhost:2359?query=1", "http://localhost:2359#fragment",
            "http://localhost:0", "http://localhost:65536", "not a URI"
    })
    void rejectsInvalidBaseUrl(String url) {
        contextRunner.withPropertyValues("runner.judge0.base-url=" + url)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({
            "java-language-id, 0", "java-language-id, -1", "java-language-id, not-a-number",
            "connect-timeout, 0ms", "request-timeout, -1s", "poll-interval, 0ms", "result-timeout, 0s",
            "poll-interval, 1ns", "request-timeout, invalid",
            "connect-timeout, 11s", "request-timeout, 61s", "poll-interval, 60s",
            "unknown-setting, 1"
    })
    void rejectsInvalidSettingsAtStartup(String property, String value) {
        contextRunner.withPropertyValues("runner.judge0." + property + "=" + value)
                .run(context -> assertThat(context).hasFailed());
    }
}
