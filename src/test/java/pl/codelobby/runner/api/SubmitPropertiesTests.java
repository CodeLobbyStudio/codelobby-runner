package pl.codelobby.runner.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.codelobby.runner.core.AsyncSubmitCoordinator;
import pl.codelobby.runner.core.EvaluationExecutor;
import pl.codelobby.runner.languages.java.JavaSubmitEvaluationExecutor;
import pl.codelobby.runner.sandbox.judge0.Judge0Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RunConfiguration.class, Judge0Configuration.class, SubmitConfiguration.class);

    @Test
    void defaultsToDisabledWithoutAnArtifactKey() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(SubmitProperties.class).enabled()).isFalse();
            assertThat(application).doesNotHaveBean(EvaluationExecutor.class).doesNotHaveBean(AsyncSubmitCoordinator.class);
        });
    }

    @Test
    void enabledConfigurationCreatesTheRealExecutorWithoutContactingJudge0() {
        context.withPropertyValues("runner.submit.enabled=true", "runner.submit.artifact-key=" + "x".repeat(64))
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(EvaluationExecutor.class)
                            .hasSingleBean(AsyncSubmitCoordinator.class);
                    assertThat(application.getBean(EvaluationExecutor.class)).isInstanceOf(JavaSubmitEvaluationExecutor.class);
                });
    }

    @Test
    void enabledSubmitRequiresAPrivateKeyAndSecretsAreNotRendered() {
        context.withPropertyValues("runner.submit.enabled=true").run(application -> assertThat(application).hasFailed());
        assertThatThrownBy(() -> new SubmitProperties(true, "short"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("short");
        assertThat(new SubmitProperties(true, "secret-" + "x".repeat(40)).toString()).doesNotContain("secret-");
    }

    @Test
    void rejectsMisspelledDeploymentOptions() {
        context.withPropertyValues("runner.submit.unknown-setting=true")
                .run(application -> assertThat(application).hasFailed());
    }
}
