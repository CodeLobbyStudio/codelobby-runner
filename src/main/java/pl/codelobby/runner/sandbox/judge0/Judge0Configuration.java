package pl.codelobby.runner.sandbox.judge0;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared HTTP transport only. Creating it does not contact Judge0. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Judge0Properties.class)
public class Judge0Configuration {

    @Bean(destroyMethod = "close")
    HttpClient judge0HttpClient(Judge0Properties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
