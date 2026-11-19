package pl.codelobby.runner.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.core.RunQueueFullException;
import pl.codelobby.runner.core.SubmissionExecutor;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RunConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RunConfiguration.class)
            .withBean(Judge0Client.class, () -> mock(Judge0Client.class));

    @Test
    void defaultsToTenWaitingOperationsWithoutContactingJudge0() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ExecutionProperties.class)
                    .hasSingleBean(ExecutionQueue.class).hasSingleBean(SubmissionExecutor.class);
            assertThat(context.getBean(ExecutionProperties.class).maxPending()).isEqualTo(10);
            assertThat(context.getBean(ExecutionProperties.class).maxActive()).isEqualTo(2);
            var queue = context.getBean(ExecutionQueue.class);
            assertThat(queue).isSameAs(context.getBean(ExecutionQueue.class));
            verifyNoInteractions(context.getBean(Judge0Client.class));
        });
    }

    @Test
    void bindsWaitingLimitFromDeploymentProperty() {
        contextRunner.withPropertyValues("runner.execution.max-pending=20").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ExecutionProperties.class).maxPending()).isEqualTo(20);
            verifyNoInteractions(context.getBean(Judge0Client.class));
        });
    }

    @Test
    void resolvesEnvironmentVariablePlaceholderFromApplicationYaml() {
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withSystemProperties("RUNNER_EXECUTION_MAX_PENDING=3", "RUNNER_EXECUTION_MAX_ACTIVE=2").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ExecutionProperties.class).maxPending()).isEqualTo(3);
                    assertThat(context.getBean(ExecutionProperties.class).maxActive()).isEqualTo(2);
                    verifyNoInteractions(context.getBean(Judge0Client.class));
                });
    }

    @Test
    void zeroWaitingLimitRejectsRunWhileSharedExecutionSlotIsOccupied() throws Exception {
        var request = JsonMapper.builder().build()
                .readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
        contextRunner.withPropertyValues("runner.execution.max-pending=0", "runner.execution.max-active=1").run(context -> {
            assertThat(context).hasNotFailed();
            var queue = context.getBean(ExecutionQueue.class);
            var executor = context.getBean(SubmissionExecutor.class);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var running = new FutureTask<>(() -> queue.execute(Duration.ofSeconds(60), remaining -> {
                entered.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return "held slot";
            }));
            var thread = new Thread(running);
            thread.start();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> executor.execute(request)).isInstanceOf(RunQueueFullException.class);
                verifyNoInteractions(context.getBean(Judge0Client.class));
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("held slot");
            var next = queue.execute(Duration.ofSeconds(60), remaining -> "available again");
            assertThat(next).isEqualTo("available again");
            thread.join(1000);
        });
    }

    @Test
    void rejectsCombinedCapacityOverflow() {
        contextRunner.withPropertyValues("runner.execution.max-active=2", "runner.execution.max-pending=2147483646")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({
            "max-pending, -1", "max-pending, 2147483647", "max-pending, 2147483648",
            "max-pending, 1.5", "max-pending, not-a-number", "max-pending, true",
            "max-active, 0", "max-active, -1", "max-active, 3", "max-active, 1.5",
            "unknown-setting, 1"
    })
    void rejectsInvalidExecutionConfigurationAtStartup(String property, String value) {
        contextRunner.withPropertyValues("runner.execution." + property + "=" + value)
                .run(context -> assertThat(context).hasFailed());
    }
}
