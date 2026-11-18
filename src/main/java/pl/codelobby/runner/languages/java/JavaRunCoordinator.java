package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.Objects;

import pl.codelobby.runner.core.ExecutionQueue;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.SubmissionExecutor;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;

/** Validates the Run language and uses shared engine capacity with a 60-second operation budget. */
public final class JavaRunCoordinator implements SubmissionExecutor {

    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private final JavaRunExecutor executor;
    private final ExecutionQueue queue;

    public JavaRunCoordinator(JavaRunExecutor executor, ExecutionQueue queue) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public RunResult execute(RunRequest request) {
        if (request.language() != Language.JAVA) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST,
                    "Java Run requires the Java language");
        }
        return queue.execute(DEADLINE, timeLeft -> executor.execute(request, timeLeft));
    }
}
