package pl.codelobby.runner.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.RunSubmissionUseCase;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SubmissionExecutor;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.languages.java.JavaRunCoordinator;
import pl.codelobby.runner.languages.java.JavaRunExecutor;
import pl.codelobby.runner.languages.java.JavaSuiteArchive;
import pl.codelobby.runner.languages.java.JavaSuitePlanner;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

@Configuration(proxyBeanMethods = false)
public class RunConfiguration {

    @Bean
    @ConditionalOnMissingBean(SubmissionExecutor.class)
    SubmissionExecutor javaRunExecutor(Judge0Client client) {
        var validator = new RunRequestValidator();
        var executor = new JavaRunExecutor(
                new JavaSuitePlanner(validator, new SourceFileOverlay(validator)), new JavaSuiteArchive(),
                client, new Judge0SuiteEvaluator(new SuiteReportParser()));
        return new JavaRunCoordinator(executor);
    }

    @Bean
    RunSubmissionUseCase runSubmissionUseCase(SubmissionExecutor executor) {
        return new RunSubmissionUseCase(new RunRequestValidator(), executor);
    }
}
