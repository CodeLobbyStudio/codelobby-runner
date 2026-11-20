package pl.codelobby.runner.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestSuite;

/** Bounded background dispatch for validated Submit payloads. Not registered as a production bean. */
public final class AsyncSubmitCoordinator implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AsyncSubmitCoordinator.class.getName());
    private final SubmitRequestValidator validator = new SubmitRequestValidator();
    private final EvaluationJobStore store;
    private final SubmitAdmission admission;
    private final EvaluationExecutor evaluator;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ConcurrentHashMap<String, JobTask> tasks = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private boolean closed;

    public AsyncSubmitCoordinator(EvaluationJobStore store, ExecutionQueue queue, EvaluationExecutor evaluator) {
        this(store, queue, evaluator,
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100),
                        threadFactory("runner-submit"), new ThreadPoolExecutor.AbortPolicy()),
                new ScheduledThreadPoolExecutor(1, threadFactory("runner-submit-deadline")));
    }

    AsyncSubmitCoordinator(EvaluationJobStore store, ExecutionQueue queue, EvaluationExecutor evaluator,
            ThreadPoolExecutor workers, ScheduledThreadPoolExecutor deadlines) {
        this.store = Objects.requireNonNull(store, "store");
        this.admission = new SubmitAdmission(store, Objects.requireNonNull(queue, "queue"));
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** Registration and dispatch order are serialized to preserve reservation FIFO across Submit callers. */
    public EvaluationJobStore.Registration submit(SubmitRequest request) {
        validator.validate(request);
        var input = snapshot(request);
        synchronized (lifecycle) {
            if (closed) {
                return store.register(input, () -> { throw new ExecutionUnavailableException(); });
            }
            var accepted = admission.admit(input);
            if (!accepted.created()) {
                return new EvaluationJobStore.Registration(accepted.job(), false);
            }
            var task = new JobTask(input, accepted.reservation());
            tasks.put(input.submissionId(), task);
            try {
                task.installDeadline(deadlines.schedule(task::timeout,
                        task.reservation.remainingTime().toNanos(), TimeUnit.NANOSECONDS));
                workers.execute(task);
            } catch (RunDeadlineExceededException exception) {
                task.fail(deadlineError(), true);
            } catch (RuntimeException exception) {
                task.fail(infrastructureError(), true);
            }
            return new EvaluationJobStore.Registration(store.find(input.submissionId()).orElseThrow(), true);
        }
    }

    /** Initiates stop without claiming that interruption proves sandbox termination. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            for (var task : List.copyOf(tasks.values())) {
                task.fail(unavailableError(), true);
            }
            workers.shutdownNow();
            deadlines.shutdownNow();
        }
    }

    private final class JobTask implements Runnable {
        private final SubmitRequest request;
        private final ExecutionQueue.Admission reservation;
        private ScheduledFuture<?> deadline;
        private Thread worker;
        private boolean running;
        private boolean done;

        private JobTask(SubmitRequest request, ExecutionQueue.Admission reservation) {
            this.request = request;
            this.reservation = reservation;
        }

        synchronized void installDeadline(ScheduledFuture<?> timer) {
            if (done) {
                timer.cancel(false);
            } else {
                deadline = timer;
            }
        }

        @Override
        public void run() {
            try {
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    worker = Thread.currentThread();
                }
                reservation.execute(remaining -> {
                    synchronized (this) {
                        if (done) {
                            return null;
                        }
                        store.start(request.submissionId());
                        running = true;
                    }
                    var result = evaluator.evaluate(request, remaining);
                    if (result == null) {
                        throw new IllegalStateException("Evaluation did not return a result");
                    }
                    synchronized (this) {
                        if (!done) {
                            reservation.remainingTime();
                            store.finish(request.submissionId(), result);
                            done = true;
                            cancelDeadline();
                        }
                    }
                    return result;
                });
            } catch (RuntimeException | Error failure) {
                JobError error;
                if (failure instanceof RunDeadlineExceededException) {
                    error = deadlineError();
                } else if (failure instanceof ExecutionUnavailableException) {
                    error = unavailableError();
                } else if (failure instanceof RequestValidationException validation) {
                    error = new JobError(validation.code().name(), "Invalid evaluation request.", false);
                } else {
                    error = infrastructureError();
                }
                fail(error, false);
                if (failure instanceof Error severe) {
                    throw severe;
                }
            } finally {
                synchronized (this) {
                    worker = null;
                    cancelDeadline();
                }
                reservation.close();
                tasks.remove(request.submissionId(), this);
            }
        }

        void timeout() {
            synchronized (this) {
                if (done) {
                    return;
                }
            }
            try {
                installDeadline(deadlines.schedule(this::timeout, reservation.remainingTime().toNanos(),
                        TimeUnit.NANOSECONDS));
            } catch (RunDeadlineExceededException exception) {
                fail(deadlineError(), true);
            } catch (RejectedExecutionException exception) {
                fail(unavailableError(), true);
            }
        }

        synchronized void fail(JobError error, boolean requestStop) {
            if (done) {
                return;
            }
            done = true;
            try {
                if (requestStop && running) {
                    reservation.quarantine();
                }
                store.fail(request.submissionId(), error);
            } finally {
                cancelDeadline();
                reservation.close();
                if (requestStop && worker != null && worker != Thread.currentThread()) {
                    worker.interrupt();
                }
                if (workers.remove(this)) {
                    tasks.remove(request.submissionId(), this);
                }
            }
        }

        private void cancelDeadline() {
            if (deadline != null) {
                deadline.cancel(false);
            }
        }
    }

    private SubmitRequest snapshot(SubmitRequest request) {
        var suites = request.testSuites().stream().map(suite -> new TestSuite(suite.suiteId(), suite.visibility(),
                List.copyOf(suite.testFilePaths()), suite.execution(), List.copyOf(suite.cases()))).toList();
        return new SubmitRequest(request.submissionId(), request.taskVersionId(), request.language(),
                List.copyOf(request.taskFiles()), List.copyOf(request.submissionFiles()), suites, request.limits());
    }

    private static ThreadFactory threadFactory(String prefix) {
        var sequence = new AtomicInteger();
        return action -> {
            var thread = new Thread(action, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) ->
                    LOG.log(System.Logger.Level.ERROR, "Submit worker terminated unexpectedly."));
            return thread;
        };
    }

    private static JobError deadlineError() {
        return new JobError("REQUEST_DEADLINE_EXCEEDED", "Request deadline exceeded.", true);
    }

    private static JobError unavailableError() {
        return new JobError("EXECUTION_UNAVAILABLE", "Execution unavailable.", true);
    }

    private static JobError infrastructureError() {
        return new JobError("EXECUTION_INFRASTRUCTURE_ERROR", "Evaluation processing failed.", true);
    }
}
