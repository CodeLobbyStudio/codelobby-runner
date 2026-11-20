package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SuiteEvaluation;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

/** One Submit suite only. The caller owns admission, aggregate budgets and uncertain-termination handling. */
public final class JavaSubmitSuiteExecutor {

    private final JavaSuitePlanner planner;
    private final JavaSuiteArchive archive;
    private final Judge0Client client;
    private final Judge0SuiteEvaluator evaluator;
    private final LongSupplier nanoTime;

    public JavaSubmitSuiteExecutor(JavaSuitePlanner planner, JavaSuiteArchive archive,
            Judge0Client client, Judge0SuiteEvaluator evaluator) {
        this(planner, archive, client, evaluator, System::nanoTime);
    }

    JavaSubmitSuiteExecutor(JavaSuitePlanner planner, JavaSuiteArchive archive,
            Judge0Client client, Judge0SuiteEvaluator evaluator, LongSupplier nanoTime) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.client = Objects.requireNonNull(client, "client");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Remaining resource budgets come from the owning pipeline, never from participant overrides. */
    public Outcome execute(SubmitRequest request, int suiteIndex, ExecutionLimits limits,
            int compilationCpuMs, int compilationWallMs, Duration timeLeft) {
        if (timeLeft == null || timeLeft.isZero() || timeLeft.isNegative()) {
            throw new RunDeadlineExceededException();
        }
        long started = nanoTime.getAsLong();
        long budget = timeLeft.compareTo(Duration.ofSeconds(180)) > 0
                ? Duration.ofSeconds(180).toNanos() : timeLeft.toNanos();
        var plans = planner.prepareSubmit(request);
        if (suiteIndex < 0 || suiteIndex >= plans.size() || limits == null
                || limits.cpuTimeMs() < 1 || limits.wallTimeMs() < limits.cpuTimeMs()
                || limits.cpuTimeMs() > request.limits().cpuTimeMs()
                || limits.wallTimeMs() > request.limits().wallTimeMs()
                || limits.memoryLimitBytes() != request.limits().memoryLimitBytes()) {
            throw new IllegalArgumentException("Invalid remaining suite budget or index");
        }
        var suite = request.testSuites().get(suiteIndex);
        byte[] zip = archive.createSubmit(plans.get(suiteIndex), suite.visibility(), compilationCpuMs, compilationWallMs);
        var token = client.submitProject(zip, limits, remaining(started, budget));
        var engine = client.awaitResult(token, remainingForActive(started, budget, token));
        // Never fetch a report for an unverified or unfinished engine result.
        if (!token.equals(engine.token()) || !engine.status().finished()) {
            throw new ExecutionUnavailableException();
        }
        var report = client.getSuiteReport(token, remaining(started, budget));
        remaining(started, budget);
        if (report.compilation() == null) {
            throw new ExecutionUnavailableException();
        }
        boolean executed = engine.status() != Judge0Status.COMPILATION_ERROR;
        if (executed && (engine.cpuTimeMs() == null || engine.wallTimeMs() == null || engine.memoryBytes() == null)) {
            throw new ExecutionUnavailableException();
        }
        return new Outcome(evaluator.evaluate(suite, engine, report), report.compilation(),
                executed ? engine.cpuTimeMs() : null, executed ? engine.wallTimeMs() : null,
                executed ? engine.memoryBytes() : null);
    }

    private Duration remaining(long started, long budget) {
        long left = budget - (nanoTime.getAsLong() - started);
        if (left <= 0) {
            throw new RunDeadlineExceededException();
        }
        return Duration.ofNanos(left);
    }

    private Duration remainingForActive(long started, long budget, Judge0SubmissionToken token) {
        try {
            return remaining(started, budget);
        } catch (RunDeadlineExceededException exception) {
            throw new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token);
        }
    }

    /** Internal accounting data with evaluated cases, without raw engine streams or report content. */
    public record Outcome(SuiteEvaluation evaluation, Judge0CompilationUsage compilation,
            Double cpuTimeMs, Double wallTimeMs, Long peakMemoryBytes) {

        public Outcome {
            Objects.requireNonNull(evaluation, "evaluation");
            Objects.requireNonNull(compilation, "compilation");
        }

        @Override
        public String toString() {
            return "SubmitSuiteOutcome[status=" + evaluation.status() + ", diagnostics=<redacted>]";
        }
    }
}
