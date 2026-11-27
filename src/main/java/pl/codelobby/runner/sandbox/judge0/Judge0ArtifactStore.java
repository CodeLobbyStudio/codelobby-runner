package pl.codelobby.runner.sandbox.judge0;

import java.time.Duration;

/** Private captured-byte transport, separate from execution and participant-facing API responses. */
public interface Judge0ArtifactStore {
    Judge0ArtifactSnapshot fetch(Judge0SubmissionToken token, Duration remainingBudget);
    /** Requires the worker's post-cleanup receipt, not merely a terminal engine status. */
    void awaitCleanup(Judge0SubmissionToken token, Duration remainingBudget);
    void delete(Judge0SubmissionToken token, Duration remainingBudget);
}
