package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SubmissionFingerprint;
import pl.codelobby.runner.core.SubmitRequestValidator;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactException;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactStore;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

/** Owned evaluation plus at most seven artifact-only rounds. Caller retains admission and handles uncertain termination. */
public final class Judge0CompiledJavaSubmission implements CompiledJavaSubmission {
    private final SubmitRequest request;
    private final String fingerprint;
    private final Judge0Client client;
    private final Judge0ArtifactStore store;
    private final Judge0SuiteEvaluator evaluator;
    private final LongSupplier nanoTime;
    private final long started;
    private final long budget;
    private final List<byte[]> archives;
    private JavaSubmitEvaluationRunner.Summary evaluation;
    private int artifactBytes;
    private int rounds;
    private boolean unavailable;
    private boolean closed;
    private boolean failed;

    public static Judge0CompiledJavaSubmission open(SubmitRequest request, JavaSubmitSuiteExecutor initial,
            Judge0Client client, Judge0ArtifactStore store, Judge0SuiteEvaluator evaluator, Duration left) {
        return open(request, initial, client, store, evaluator, left, System::nanoTime);
    }

    static Judge0CompiledJavaSubmission open(SubmitRequest request, JavaSubmitSuiteExecutor initial,
            Judge0Client client, Judge0ArtifactStore store, Judge0SuiteEvaluator evaluator, Duration left,
            LongSupplier clock) {
        var session = new Judge0CompiledJavaSubmission(request, client, store, evaluator, left, clock);
        try {
            session.evaluation = new JavaSubmitEvaluationRunner(initial, clock)
                    .evaluate(session.request, session.remaining(), session::capture);
            if (session.evaluation.status() != Verdict.ACCEPTED) {
                session.releaseArchives();
            }
            return session;
        } catch (RuntimeException | Error failure) {
            session.close();
            throw failure;
        }
    }

    private Judge0CompiledJavaSubmission(SubmitRequest input, Judge0Client client, Judge0ArtifactStore store,
            Judge0SuiteEvaluator evaluator, Duration left, LongSupplier clock) {
        this.nanoTime = Objects.requireNonNull(clock, "clock");
        this.started = clock.getAsLong();
        if (left == null || left.isNegative() || left.isZero()) {
            throw new RunDeadlineExceededException();
        }
        this.budget = left.compareTo(Duration.ofSeconds(180)) > 0 ? Duration.ofSeconds(180).toNanos() : left.toNanos();
        new SubmitRequestValidator().validate(input);
        this.request = new SubmitRequest(input.submissionId(), input.taskVersionId(), input.language(),
                List.copyOf(input.taskFiles()), List.copyOf(input.submissionFiles()),
                input.testSuites().stream().map(suite -> new TestSuite(suite.suiteId(), suite.visibility(),
                        List.copyOf(suite.testFilePaths()), suite.execution(), List.copyOf(suite.cases()))).toList(), input.limits());
        new SubmitRequestValidator().validate(request);
        this.fingerprint = SubmissionFingerprint.compute(request);
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.archives = new ArrayList<>(java.util.Collections.nCopies(request.testSuites().size(), null));
    }

    private void capture(int index, JavaSubmitSuiteExecutor.Outcome outcome, Duration left) {
        if (outcome.token() == null) {
            throw new ExecutionUnavailableException();
        }
        verifyCleanup(outcome.token(), smaller(left, remaining()));
        try {
            if (outcome.evaluation().status() != Verdict.ACCEPTED) {
                unavailable = true;
                releaseArchives();
            }
            if (!unavailable) {
                var snapshot = store.fetch(outcome.token(), smaller(left, remaining()));
                var classes = JavaCompiledArtifacts.read(snapshot.content(), snapshot.sha256());
                if (artifactBytes + classes.byteCount() > 8 * 1048576) {
                    unavailable = true;
                    releaseArchives();
                } else {
                    var execution = (JavaExecution) request.testSuites().get(index).execution();
                    archives.set(index, new JavaCompiledSuiteArchive().create(classes, execution.mainClass()));
                    artifactBytes += classes.byteCount();
                }
            }
        } catch (Judge0ArtifactException | IllegalArgumentException failure) {
            if (failure instanceof Judge0ArtifactException artifactFailure
                    && artifactFailure.reason() == Judge0ArtifactException.Reason.INTERRUPTED) {
                Thread.currentThread().interrupt();
                throw new ExecutionUnavailableException();
            }
            unavailable = true;
            releaseArchives();
        } finally {
            deleteCaptured(outcome.token());
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new ExecutionUnavailableException();
        }
    }

    private void verifyCleanup(Judge0SubmissionToken token, Duration left) {
        try {
            store.awaitCleanup(token, smaller(left, remaining()));
        } catch (Judge0ArtifactException failure) {
            if (failure.reason() == Judge0ArtifactException.Reason.INTERRUPTED) {
                Thread.currentThread().interrupt();
            }
            remaining();
            throw new ExecutionUnavailableException();
        }
        remaining();
    }

    private void deleteCaptured(Judge0SubmissionToken token) {
        long left = budget - (nanoTime.getAsLong() - started);
        if (left <= 0 || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            store.delete(token, Duration.ofNanos(Math.min(left, Duration.ofSeconds(1).toNanos())));
        } catch (Judge0ArtifactException failure) {
            if (failure.reason() == Judge0ArtifactException.Reason.INTERRUPTED) {
                Thread.currentThread().interrupt();
            }
            // Private cache expiry remains bounded if best-effort deletion is unavailable.
        }
    }

    @Override
    public String submissionId() { return request.submissionId(); }

    @Override
    public String payloadFingerprint() { return fingerprint; }

    @Override
    public JavaSubmitEvaluationRunner.Summary evaluation() { return evaluation; }

    @Override
    public synchronized JavaSubmitEvaluationRunner.Summary executeRound(ExecutionLimits limits, Duration left) {
        if (closed || failed || rounds >= 7 || evaluation.status() != Verdict.ACCEPTED) {
            throw new IllegalStateException("Compiled submission session cannot execute another round");
        }
        if (!request.limits().equals(limits)) {
            throw new IllegalArgumentException("Benchmark rounds require the original execution limits");
        }
        if (left == null || left.isNegative() || left.isZero()) {
            throw new RunDeadlineExceededException();
        }
        Duration roundBudget = smaller(left, remaining());
        rounds++;
        if (unavailable) {
            return unavailableSummary();
        }
        try {
            var result = JavaSubmitEvaluationRunner.rounds(this::replay, nanoTime)
                    .evaluate(request, roundBudget);
            remaining();
            failed = result.status() != Verdict.ACCEPTED;
            return result;
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    private JavaSubmitSuiteExecutor.Outcome replay(SubmitRequest ignored, int index, ExecutionLimits limits,
            int ignoredBuildCpu, int ignoredBuildWall, Duration left) {
        byte[] archive = archives.get(index);
        if (archive == null) {
            throw new ExecutionUnavailableException();
        }
        long phaseStarted = nanoTime.getAsLong();
        long phaseBudget = smaller(left, remaining()).toNanos();
        var token = client.submitProject(archive.clone(), limits, phaseRemaining(phaseStarted, phaseBudget));
        Duration pollLeft;
        try {
            pollLeft = phaseRemaining(phaseStarted, phaseBudget);
        } catch (RunDeadlineExceededException failure) {
            throw new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token);
        }
        var engine = client.awaitResult(token, pollLeft);
        if (!token.equals(engine.token()) || !engine.status().finished()) {
            throw new Judge0ClientException(Judge0ClientException.Reason.INVALID_RESPONSE, token);
        }
        var report = client.getSuiteReport(token, phaseRemaining(phaseStarted, phaseBudget));
        phaseRemaining(phaseStarted, phaseBudget);
        if (report.compilation() == null || report.compilation().cpuTimeMs() != 0 || report.compilation().wallTimeMs() != 0
                || engine.cpuTimeMs() == null || engine.wallTimeMs() == null || engine.memoryBytes() == null) {
            throw new ExecutionUnavailableException();
        }
        verifyCleanup(token, phaseRemaining(phaseStarted, phaseBudget));
        deleteCaptured(token);
        if (Thread.currentThread().isInterrupted()) {
            throw new ExecutionUnavailableException();
        }
        return new JavaSubmitSuiteExecutor.Outcome(evaluator.evaluate(request.testSuites().get(index), engine, report),
                report.compilation(), engine.cpuTimeMs(), engine.wallTimeMs(), engine.memoryBytes(), token);
    }

    private JavaSubmitEvaluationRunner.Summary unavailableSummary() {
        var cases = request.testSuites().stream().flatMap(suite -> suite.cases().stream().map(test ->
                new TestResult(test.testId(), suite.suiteId(), suite.visibility(), TestVerdict.NOT_RUN,
                        null, null, null, null, null))).toList();
        return new JavaSubmitEvaluationRunner.Summary(Verdict.EXECUTION_ERROR, 0, cases.size(), cases,
                new Judge0CompilationUsage(0, 0), null, null, null);
    }

    private Duration phaseRemaining(long phaseStarted, long phaseBudget) {
        long left = Math.min(phaseBudget - (nanoTime.getAsLong() - phaseStarted),
                budget - (nanoTime.getAsLong() - started));
        if (left <= 0) {
            throw new RunDeadlineExceededException();
        }
        return Duration.ofNanos(left);
    }

    private Duration remaining() {
        return phaseRemaining(started, budget);
    }

    private static Duration smaller(Duration left, Duration right) {
        return left.compareTo(right) < 0 ? left : right;
    }

    private void releaseArchives() {
        java.util.Collections.fill(archives, null);
        artifactBytes = 0;
    }

    @Override
    public synchronized void close() {
        closed = true;
        releaseArchives();
    }

    @Override
    public String toString() { return "Judge0CompiledJavaSubmission[<redacted>]"; }
}
