package pl.codelobby.runner.languages.java;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunQueueFullException;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.RunResult;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JavaRunCoordinatorTests {

    private final JavaRunExecutor executor = mock(JavaRunExecutor.class);
    private RunRequest request;
    private RunResult result;

    @BeforeEach
    void readExamples() throws Exception {
        var mapper = JsonMapper.builder().build();
        request = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
        result = mapper.readValue(Path.of("docs/api/examples/run-accepted.json").toFile(), RunResponse.class).result();
    }

    @Test
    void rejectsUnsupportedLanguagesBeforeAdmission() {
        var queue = new ExecutionQueue(0);
        var coordinator = new JavaRunCoordinator(executor, queue);
        var unsupported = new RunRequest(request.requestId(), request.taskVersionId(), Language.PYTHON,
                request.taskFiles(), request.submissionFiles(), request.testSuites(), request.limits());

        assertThatThrownBy(() -> coordinator.execute(unsupported))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ValidationErrorCode.INVALID_REQUEST));
        verifyNoInteractions(executor);
        var nextResult = queue.execute(Duration.ofSeconds(60), remaining -> "available");
        assertThat(nextResult).isEqualTo("available");
    }

    @Test
    void passesTheRunBudgetAndReturnsTheExecutorResult() {
        var coordinator = new JavaRunCoordinator(executor, new ExecutionQueue(10));
        when(executor.execute(any(), any())).thenReturn(result);

        assertThat(coordinator.execute(request)).isEqualTo(result);
        verify(executor).execute(eq(request),
                argThat(budget -> !budget.isZero() && !budget.isNegative()
                        && budget.compareTo(Duration.ofSeconds(60)) <= 0));
    }

    @Test
    void independentCoordinatorsUseTheSameExecutionCapacity() throws Exception {
        var queue = new ExecutionQueue(0);
        var firstCoordinator = new JavaRunCoordinator(executor, queue);
        var otherExecutor = mock(JavaRunExecutor.class);
        var secondCoordinator = new JavaRunCoordinator(otherExecutor, queue);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(executor.execute(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        });
        when(otherExecutor.execute(any(), any())).thenReturn(result);
        var running = new FutureTask<>(() -> firstCoordinator.execute(request));
        var thread = new Thread(running);
        thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> secondCoordinator.execute(request)).isInstanceOf(RunQueueFullException.class);
            verifyNoInteractions(otherExecutor);
        } finally {
            release.countDown();
        }
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(secondCoordinator.execute(request)).isEqualTo(result);
        thread.join(1000);
    }
}
