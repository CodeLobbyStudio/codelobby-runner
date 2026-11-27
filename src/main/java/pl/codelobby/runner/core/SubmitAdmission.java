package pl.codelobby.runner.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.SubmitRequest;

/** Atomic job registration and shared capacity reservation, without dispatch or execution. */
public final class SubmitAdmission {

    private static final Duration DEADLINE = Duration.ofSeconds(180);
    private final EvaluationJobStore store;
    private final ExecutionQueue queue;

    public SubmitAdmission(EvaluationJobStore store, ExecutionQueue queue) {
        this.store = Objects.requireNonNull(store, "store");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /** Input must already be validated. The dispatcher must own and eventually use or close a new reservation. */
    public AdmittedJob admit(SubmitRequest request) {
        var reserved = new AtomicReference<ExecutionQueue.Admission>();
        try {
            var registration = store.register(request, () -> reserved.set(queue.reserve(DEADLINE)));
            return new AdmittedJob(registration.job(), registration.created(), reserved.get());
        } catch (RuntimeException | Error exception) {
            var reservation = reserved.get();
            if (reservation != null) {
                reservation.close();
            }
            throw exception;
        }
    }

    /** Replays have no reservation and must not be dispatched again. Dispatch cancellation must also fail the job. */
    public record AdmittedJob(EvaluationJob job, boolean created, ExecutionQueue.Admission reservation) {
        public AdmittedJob {
            Objects.requireNonNull(job, "job");
            if (created != (reservation != null)) {
                throw new IllegalArgumentException("Only a new job owns an execution reservation");
            }
        }
    }
}
