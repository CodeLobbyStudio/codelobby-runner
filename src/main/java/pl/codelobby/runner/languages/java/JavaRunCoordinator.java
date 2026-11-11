package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.RunQueueFullException;
import pl.codelobby.runner.core.SubmissionExecutor;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;

/** One active Run and ten pending Runs with a deadline measured from admission. */
public final class JavaRunCoordinator implements SubmissionExecutor {

    private static final long DEADLINE_NANOS = Duration.ofSeconds(60).toNanos();
    private final JavaRunExecutor executor;
    private final Semaphore capacity;
    private final ReentrantLock pipeline = new ReentrantLock(true);
    private final LongSupplier nanoTime;
    private volatile boolean quarantined;

    public JavaRunCoordinator(JavaRunExecutor executor) {
        this(executor, 10, System::nanoTime);
    }

    JavaRunCoordinator(JavaRunExecutor executor, int pendingCapacity, LongSupplier nanoTime) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.capacity = new Semaphore(1 + pendingCapacity);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public RunResult execute(RunRequest request) {
        if (request.language() != Language.JAVA) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST,
                    "Java Run requires the Java language");
        }
        if (quarantined) {
            throw new ExecutionUnavailableException();
        }
        if (!capacity.tryAcquire()) {
            throw new RunQueueFullException();
        }
        long started = nanoTime.getAsLong();
        boolean locked = false;
        try {
            long remaining = remainingNanos(started);
            locked = pipeline.tryLock(remaining, TimeUnit.NANOSECONDS);
            if (!locked) {
                throw new RunDeadlineExceededException();
            }
            if (quarantined) {
                throw new ExecutionUnavailableException();
            }
            var timeLeft = Duration.ofNanos(remainingNanos(started));
            try {
                return executor.execute(request, timeLeft);
            } catch (RequestValidationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                // Communication failures can leave a submission running or cleanup unconfirmed.
                quarantined = true;
                throw exception;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExecutionUnavailableException();
        } finally {
            if (locked) {
                pipeline.unlock();
            }
            capacity.release();
        }
    }

    private long remainingNanos(long started) {
        long remaining = DEADLINE_NANOS - (nanoTime.getAsLong() - started);
        if (remaining <= 0) {
            throw new RunDeadlineExceededException();
        }
        return remaining;
    }
}
