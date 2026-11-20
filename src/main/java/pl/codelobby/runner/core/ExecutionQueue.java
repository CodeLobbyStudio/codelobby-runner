package pl.codelobby.runner.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Shared process-local admission. Reservations keep FIFO order before execution is dispatched. */
public final class ExecutionQueue {

    private final int activeCapacity;
    private final int totalCapacity;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Admission> waiting = new ArrayDeque<>();
    private final LongSupplier nanoTime;
    private int active;
    private int admitted;
    private boolean quarantined;

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
        this.activeCapacity = activeCapacity;
        this.totalCapacity = activeCapacity + pendingCapacity;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Reserve before publishing an accepted asynchronous job. The owner must execute or close it. */
    public Admission reserve(Duration timeout) {
        long budget = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (budget <= 0) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        lock.lock();
        try {
            if (quarantined) {
                throw new ExecutionUnavailableException();
            }
            if (admitted >= totalCapacity) {
                throw new RunQueueFullException();
            }
            var admission = new Admission(nanoTime.getAsLong(), budget);
            waiting.addLast(admission);
            admitted++;
            return admission;
        } finally {
            lock.unlock();
        }
    }

    /** Synchronous callers use the same admission path and remaining operation budget. */
    public <T> T execute(Duration timeout, Function<Duration, T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (var admission = reserve(timeout)) {
            return admission.execute(operation);
        }
    }

    public final class Admission implements AutoCloseable {
        private final long started;
        private final long budget;
        private final AtomicBoolean used = new AtomicBoolean();
        private State state = State.RESERVED;

        private Admission(long started, long budget) {
            this.started = started;
            this.budget = budget;
        }

        public <T> T execute(Function<Duration, T> operation) {
            Objects.requireNonNull(operation, "operation");
            if (!used.compareAndSet(false, true)) {
                throw new IllegalStateException("Execution reservation can only be used once");
            }
            try {
                lock.lockInterruptibly();
                try {
                    if (state == State.CLOSED) {
                        throw new IllegalStateException("Execution reservation is closed");
                    }
                    state = State.WAITING;
                    while (true) {
                        if (quarantined || state == State.CLOSED) {
                            throw new ExecutionUnavailableException();
                        }
                        long remaining = remainingNanos();
                        if (waiting.peekFirst() == this && active < activeCapacity) {
                            waiting.removeFirst();
                            active++;
                            state = State.RUNNING;
                            changed.signalAll();
                            break;
                        }
                        changed.awaitNanos(remaining);
                    }
                } finally {
                    lock.unlock();
                }
                var timeLeft = Duration.ofNanos(remainingNanos());
                try {
                    return operation.apply(timeLeft);
                } catch (RequestValidationException exception) {
                    throw exception;
                } catch (RuntimeException | Error exception) {
                    lock.lock();
                    try {
                        // Admission release is not proof of engine termination or cleanup.
                        quarantined = true;
                        changed.signalAll();
                    } finally {
                        lock.unlock();
                    }
                    throw exception;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExecutionUnavailableException();
            } finally {
                lock.lock();
                try {
                    if (state == State.RUNNING) {
                        active--;
                    }
                    release();
                } finally {
                    lock.unlock();
                }
            }
        }

        /** Closing an unused or waiting reservation cancels it. Running work retains capacity. */
        @Override
        public void close() {
            lock.lock();
            try {
                if (state != State.RUNNING) {
                    release();
                }
            } finally {
                lock.unlock();
            }
        }

        private void release() {
            if (state != State.CLOSED) {
                waiting.remove(this);
                state = State.CLOSED;
                admitted--;
                changed.signalAll();
            }
        }

        private long remainingNanos() {
            long remaining = budget - (nanoTime.getAsLong() - started);
            if (remaining <= 0) {
                throw new RunDeadlineExceededException();
            }
            return remaining;
        }
    }

    private enum State {
        RESERVED,
        WAITING,
        RUNNING,
        CLOSED
    }
}
