package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.EvaluationExecutor;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.GradingIntegrity;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.sandbox.judge0.Judge0ArtifactStore;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

/** Real evaluation plus benchmark, independent of HTTP. The coordinator owns admission. */
public final class JavaSubmitEvaluationExecutor implements EvaluationExecutor {

    @FunctionalInterface
    interface SessionFactory {
        CompiledJavaSubmission open(SubmitRequest request, Duration left);
    }

    private final SessionFactory sessions;
    private final JavaSubmitBenchmarkRunner benchmark;
    private final LongSupplier clock;

    public JavaSubmitEvaluationExecutor(JavaSubmitSuiteExecutor initial, Judge0Client client,
            Judge0ArtifactStore artifacts, Judge0SuiteEvaluator evaluator) {
        this(sessionFactory(initial, client, artifacts, evaluator), new JavaSubmitBenchmarkRunner(), System::nanoTime);
    }

    private static SessionFactory sessionFactory(JavaSubmitSuiteExecutor initial, Judge0Client client,
            Judge0ArtifactStore artifacts, Judge0SuiteEvaluator evaluator) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(evaluator, "evaluator");
        return (request, left) -> Judge0CompiledJavaSubmission.open(request, initial, client, artifacts, evaluator, left);
    }

    JavaSubmitEvaluationExecutor(SessionFactory sessions, JavaSubmitBenchmarkRunner benchmark, LongSupplier clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.benchmark = Objects.requireNonNull(benchmark, "benchmark");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EvaluationResult evaluate(SubmitRequest request, Duration left) {
        if (left == null || left.isZero() || left.isNegative()) {
            throw new RunDeadlineExceededException();
        }
        long started = clock.getAsLong();
        long budget = left.compareTo(Duration.ofSeconds(180)) > 0 ? Duration.ofSeconds(180).toNanos() : left.toNanos();
        EvaluationResult result;
        try (var session = sessions.open(request, remaining(started, budget))) {
            var evaluation = session.evaluation();
            var measured = benchmark.run(request, session, remaining(started, budget));
            remaining(started, budget);
            result = new EvaluationResult(evaluation.status(), evaluation.passedTests(), evaluation.totalTests(),
                    evaluation.testResults(), null, null, evaluation.sandboxCpuTimeMs(), evaluation.sandboxPeakMemoryBytes(),
                    measured, GradingIntegrity.HOST_TESTS_IN_PARTICIPANT_SANDBOX);
        }
        remaining(started, budget);
        return result;
    }

    private Duration remaining(long started, long budget) {
        long left = budget - (clock.getAsLong() - started);
        if (left <= 0) {
            throw new RunDeadlineExceededException();
        }
        return Duration.ofNanos(left);
    }
}
