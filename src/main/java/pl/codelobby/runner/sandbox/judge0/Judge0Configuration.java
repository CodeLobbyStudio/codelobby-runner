package pl.codelobby.runner.sandbox.judge0;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

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

    @Bean
    Judge0Client judge0Client(@Qualifier("judge0HttpClient") HttpClient http, Judge0Properties properties) {
        return new HttpJudge0Client(http, properties);
    }
}
