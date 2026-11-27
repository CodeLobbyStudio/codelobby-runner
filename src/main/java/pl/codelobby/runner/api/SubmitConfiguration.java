package pl.codelobby.runner.api;

import java.net.http.HttpClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.codelobby.runner.core.AsyncSubmitCoordinator;
import pl.codelobby.runner.core.EvaluationExecutor;
import pl.codelobby.runner.core.EvaluationJobStore;
import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.core.InMemoryEvaluationJobStore;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SubmitJobUseCase;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.languages.java.JavaSubmitEvaluationExecutor;
import pl.codelobby.runner.languages.java.JavaSubmitSuiteExecutor;
import pl.codelobby.runner.languages.java.JavaSuiteArchive;
import pl.codelobby.runner.languages.java.JavaSuitePlanner;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0ArtifactStore;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0Properties;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubmitProperties.class)
public class SubmitConfiguration {

    @Bean
    @ConditionalOnMissingBean(EvaluationJobStore.class)
    EvaluationJobStore evaluationJobStore() {
        return new InMemoryEvaluationJobStore();
    }

    @Bean
    @ConditionalOnProperty(name = "runner.submit.enabled", havingValue = "true")
    @ConditionalOnMissingBean(EvaluationExecutor.class)
    EvaluationExecutor submitExecutor(@Qualifier("judge0HttpClient") HttpClient http, Judge0Client client,
            Judge0Properties engine, SubmitProperties properties) {
        var validator = new RunRequestValidator();
        var evaluator = new Judge0SuiteEvaluator(new SuiteReportParser());
        var initial = new JavaSubmitSuiteExecutor(new JavaSuitePlanner(validator, new SourceFileOverlay(validator)),
                new JavaSuiteArchive(), client, evaluator);
        return new JavaSubmitEvaluationExecutor(initial, client,
                new HttpJudge0ArtifactStore(http, engine, properties.artifactKey()), evaluator);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "runner.submit.enabled", havingValue = "true")
    AsyncSubmitCoordinator asyncSubmitCoordinator(EvaluationJobStore jobs, ExecutionQueue queue, EvaluationExecutor executor) {
        return new AsyncSubmitCoordinator(jobs, queue, executor);
    }

    @Bean
    SubmitJobUseCase submitJobUseCase(EvaluationJobStore jobs, ObjectProvider<AsyncSubmitCoordinator> coordinator,
            SubmitProperties properties) {
        return new SubmitJobUseCase(jobs, () -> properties.enabled() ? coordinator.getIfAvailable() : null);
    }
}
