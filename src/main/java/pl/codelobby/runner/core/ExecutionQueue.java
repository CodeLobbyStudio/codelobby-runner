package pl.codelobby.runner.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Shared in-memory admission with one active operation and bounded waiting capacity.
 * Callers execute synchronously and must share this instance. No jobs or results are persisted.
 */
public final class ExecutionQueue {

    private final Semaphore capacity;
    private final ReentrantLock pipeline = new ReentrantLock(true);
    private final LongSupplier nanoTime;
    private volatile boolean quarantined;

    public ExecutionQueue(int pendingCapacity) {
        this(pendingCapacity, System::nanoTime);
    }

    ExecutionQueue(int pendingCapacity, LongSupplier nanoTime) {
        if (pendingCapacity < 0 || pendingCapacity == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Pending capacity must allow one additional active operation");
        }
        this.capacity = new Semaphore(1 + pendingCapacity);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** The operation receives its remaining budget after waiting for the shared execution slot. */
    public <T> T execute(Duration timeout, Function<Duration, T> operation) {
        Objects.requireNonNull(operation, "operation");
        long budget = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (budget <= 0) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        if (quarantined) {
            throw new ExecutionUnavailableException();
        }
        if (!capacity.tryAcquire()) {
            throw new RunQueueFullException();
        }
        boolean locked = false;
        try {
            long started = nanoTime.getAsLong();
            locked = pipeline.tryLock(remainingNanos(started, budget), TimeUnit.NANOSECONDS);
            if (!locked) {
                throw new RunDeadlineExceededException();
            }
            if (quarantined) {
                throw new ExecutionUnavailableException();
            }
            var timeLeft = Duration.ofNanos(remainingNanos(started, budget));
            try {
                return operation.apply(timeLeft);
            } catch (RequestValidationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                // Releasing admission does not confirm engine termination or cleanup.
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

    private long remainingNanos(long started, long budget) {
        long remaining = budget - (nanoTime.getAsLong() - started);
        if (remaining <= 0) {
            throw new RunDeadlineExceededException();
        }
        return remaining;
    }
}
