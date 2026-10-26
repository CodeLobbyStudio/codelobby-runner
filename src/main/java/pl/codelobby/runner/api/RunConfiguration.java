package pl.codelobby.runner.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.RunSubmissionUseCase;
import pl.codelobby.runner.core.SubmissionExecutor;

@Configuration(proxyBeanMethods = false)
public class RunConfiguration {

    @Bean
    @ConditionalOnMissingBean(SubmissionExecutor.class)
    SubmissionExecutor unavailableExecutor() {
        return request -> {
            throw new ExecutionUnavailableException();
        };
    }

    @Bean
    RunSubmissionUseCase runSubmissionUseCase(SubmissionExecutor executor) {
        return new RunSubmissionUseCase(new RunRequestValidator(), executor);
    }
}
