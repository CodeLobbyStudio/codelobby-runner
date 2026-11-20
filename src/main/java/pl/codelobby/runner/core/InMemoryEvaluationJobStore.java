package pl.codelobby.runner.core;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.JobState;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import tools.jackson.databind.JsonNode;

/** Bounded, process-local snapshots and fingerprints. Does not store source payloads or schedule work. */
public final class InMemoryEvaluationJobStore implements EvaluationJobStore {

    private static final int MAX_JOBS = 100;
    private static final long RETENTION_SECONDS = 1800;
    private static final String CONTRACT_VERSION = "1.0.0-draft.2";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final Map<String, Entry> jobs = new HashMap<>();
    private final Clock clock;

    public InMemoryEvaluationJobStore() {
        this(Clock.systemUTC());
    }

    public InMemoryEvaluationJobStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Full request validation and ownership of immutable execution payloads belong to the caller. */
    @Override
    public Registration register(SubmitRequest request) {
        Objects.requireNonNull(request, "request");
        validId(request.submissionId());
        validId(request.taskVersionId());
        String fingerprint = SubmissionFingerprint.compute(request);
        synchronized (this) {
            Instant now = clock.instant();
            purgeExpired(now);
            var existing = jobs.get(request.submissionId());
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new SubmissionIdConflictException();
                }
                return new Registration(snapshot(existing.job()), false);
            }
            if (jobs.size() >= MAX_JOBS) {
                throw new JobStoreFullException();
            }
            var job = new EvaluationJob(request.submissionId(), request.taskVersionId(), CONTRACT_VERSION,
                    JobState.QUEUED, now, null, null, null, null, null);
            jobs.put(request.submissionId(), new Entry(fingerprint, job));
            return new Registration(job, true);
        }
    }

    @Override
    public synchronized Optional<EvaluationJob> find(String submissionId) {
        validId(submissionId);
        purgeExpired(clock.instant());
        var entry = jobs.get(submissionId);
        return entry == null ? Optional.empty() : Optional.of(snapshot(entry.job()));
    }

    @Override
    public synchronized EvaluationJob start(String submissionId) {
        var entry = required(submissionId);
        var previous = entry.job();
        if (previous.state() != JobState.QUEUED) {
            throw new IllegalStateException("Only queued jobs can start");
        }
        var job = new EvaluationJob(previous.submissionId(), previous.taskVersionId(), CONTRACT_VERSION,
                JobState.RUNNING, previous.createdAt(), clock.instant(), null, null, null, null);
        jobs.put(submissionId, new Entry(entry.fingerprint(), job));
        return job;
    }

    @Override
    public synchronized EvaluationJob finish(String submissionId, EvaluationResult result) {
        Objects.requireNonNull(result, "result");
        var entry = required(submissionId);
        var previous = entry.job();
        if (previous.state() != JobState.RUNNING) {
            throw new IllegalStateException("Only running jobs can finish");
        }
        Instant now = clock.instant();
        var job = new EvaluationJob(previous.submissionId(), previous.taskVersionId(), CONTRACT_VERSION,
                JobState.FINISHED, previous.createdAt(), previous.startedAt(), now, now.plusSeconds(RETENTION_SECONDS),
                copyResult(result), null);
        jobs.put(submissionId, new Entry(entry.fingerprint(), job));
        return snapshot(job);
    }

    @Override
    public synchronized EvaluationJob fail(String submissionId, JobError error) {
        Objects.requireNonNull(error, "error");
        var entry = required(submissionId);
        var previous = entry.job();
        if (previous.state() != JobState.QUEUED && previous.state() != JobState.RUNNING) {
            throw new IllegalStateException("Terminal jobs cannot fail again or restart");
        }
        Instant now = clock.instant();
        var job = new EvaluationJob(previous.submissionId(), previous.taskVersionId(), CONTRACT_VERSION,
                JobState.FAILED, previous.createdAt(), previous.startedAt(), now, now.plusSeconds(RETENTION_SECONDS),
                null, error);
        jobs.put(submissionId, new Entry(entry.fingerprint(), job));
        return job;
    }

    private Entry required(String submissionId) {
        validId(submissionId);
        purgeExpired(clock.instant());
        var entry = jobs.get(submissionId);
        if (entry == null) {
            throw new NoSuchElementException("Evaluation job not found");
        }
        return entry;
    }

    private void purgeExpired(Instant now) {
        jobs.values().removeIf(entry -> entry.job().expiresAt() != null && !entry.job().expiresAt().isAfter(now));
    }

    private void validId(String id) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Invalid job identifier");
        }
    }

    private EvaluationJob snapshot(EvaluationJob job) {
        if (job.result() == null) {
            return job;
        }
        return new EvaluationJob(job.submissionId(), job.taskVersionId(), job.contractVersion(), job.state(),
                job.createdAt(), job.startedAt(), job.finishedAt(), job.expiresAt(), copyResult(job.result()), job.error());
    }

    private EvaluationResult copyResult(EvaluationResult result) {
        var cases = result.testResults().stream().map(value -> new TestResult(value.testId(), value.suiteId(),
                value.visibility(), value.status(), copyNode(value.input()), copyNode(value.expectedOutput()),
                copyNode(value.actualOutput()), value.stdout(), value.stderr())).toList();
        return new EvaluationResult(result.status(), result.passedTests(), result.totalTests(), cases,
                result.compileOutput(), result.message(), result.sandboxCpuTimeMs(), result.sandboxPeakMemoryBytes(),
                result.benchmark(), result.gradingIntegrity());
    }

    private JsonNode copyNode(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    private record Entry(String fingerprint, EvaluationJob job) {
    }
}
