package pl.codelobby.runner.languages.java;

import java.time.Duration;

import pl.codelobby.runner.model.ExecutionLimits;

/**
 * One already evaluated request and its immutable compiled artifacts.
 * Each round uses those exact artifacts, fresh sandboxes and fresh aggregate limits.
 * A round may return only after verified process cleanup. Uncertain termination must throw.
 * The owner holds admission capacity and closes the session after all rounds or failures.
 * No production Judge0 implementation is registered yet.
 */
public interface CompiledJavaSubmission extends AutoCloseable {

    String submissionId();

    /** Fingerprint of the validated payload whose sources produced these artifacts. */
    String payloadFingerprint();

    JavaSubmitEvaluationRunner.Summary evaluation();

    /** No recompilation and no writable runtime state carried across suites or rounds. */
    JavaSubmitEvaluationRunner.Summary executeRound(ExecutionLimits limits, Duration remainingBudget);

    @Override
    void close();
}
