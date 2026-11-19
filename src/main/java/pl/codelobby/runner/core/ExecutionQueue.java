package pl.codelobby.runner.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Shared in-memory admission with configurable active and bounded waiting capacity.
 * Callers execute synchronously and must share this instance. No jobs or results are persisted.
 */
public final class ExecutionQueue {

    private final Semaphore capacity;
    private final Semaphore pipeline;
    private final LongSupplier nanoTime;
    private volatile boolean quarantined;

    public ExecutionQueue(int pendingCapacity) {
        this(1, pendingCapacity, System::nanoTime);
    }

    public ExecutionQueue(int activeCapacity, int pendingCapacity) {
        this(activeCapacity, pendingCapacity, System::nanoTime);
    }

    ExecutionQueue(int pendingCapacity, LongSupplier nanoTime) {
        this(1, pendingCapacity, nanoTime);
    }

    ExecutionQueue(int activeCapacity, int pendingCapacity, LongSupplier nanoTime) {
        if (activeCapacity < 1 || activeCapacity > 2 || pendingCapacity < 0
                || (long) activeCapacity + pendingCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Active capacity must be 1 or 2 and total capacity must fit an integer");
        }
        this.capacity = new Semaphore(activeCapacity + pendingCapacity);
        this.pipeline = new Semaphore(activeCapacity, true);
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
            locked = pipeline.tryAcquire(remainingNanos(started, budget), TimeUnit.NANOSECONDS);
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
                pipeline.release();
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
