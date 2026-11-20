package pl.codelobby.runner.model;

import java.time.Instant;
import java.util.Objects;

/** A consistent job snapshot, without storage, state transitions or HTTP behavior. */
public record EvaluationJob(
        String submissionId,
        String taskVersionId,
        String contractVersion,
        JobState state,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt,
        EvaluationResult result,
        JobError error) {

    public EvaluationJob {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        if (submissionId == null || submissionId.isBlank() || taskVersionId == null || taskVersionId.isBlank()
                || !"1.0.0-draft.2".equals(contractVersion)) {
            throw new IllegalArgumentException("Invalid job identity or contract version");
        }
        boolean valid = switch (state) {
            case QUEUED -> startedAt == null && finishedAt == null && expiresAt == null
                    && result == null && error == null;
            case RUNNING -> startedAt != null && finishedAt == null && expiresAt == null
                    && result == null && error == null;
            case FINISHED -> startedAt != null && finishedAt != null && expiresAt != null
                    && result != null && error == null;
            case FAILED -> finishedAt != null && expiresAt != null && result == null && error != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Job state, timestamps, result and error are inconsistent");
        }
        if ((startedAt != null && startedAt.isBefore(createdAt))
                || (finishedAt != null && finishedAt.isBefore(startedAt == null ? createdAt : startedAt))
                || (finishedAt != null && !expiresAt.equals(finishedAt.plusSeconds(1800)))) {
            throw new IllegalArgumentException("Job timestamps must be ordered with thirty-minute terminal retention");
        }
    }

    @Override
    public String toString() {
        return "EvaluationJob[<redacted>]";
    }
}
