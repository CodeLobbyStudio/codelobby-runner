package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SubmissionExecutor;
import pl.codelobby.runner.model.Benchmark;
import pl.codelobby.runner.model.BenchmarkReason;
import pl.codelobby.runner.model.BenchmarkState;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.GradingIntegrity;
import pl.codelobby.runner.model.MeasurementScope;
import pl.codelobby.runner.model.MeasurementTrust;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0ClientException;
import pl.codelobby.runner.sandbox.judge0.Judge0SubmissionToken;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

import static pl.codelobby.runner.core.SuiteEvaluation.higherPriority;

/** Sequential public Java execution with request-wide compiler and evaluation budgets. */
public final class JavaRunExecutor implements SubmissionExecutor {

    private static final long DEADLINE_NANOS = Duration.ofSeconds(60).toNanos();
    private static final Benchmark NO_BENCHMARK = new Benchmark(
            BenchmarkState.NOT_REQUESTED, BenchmarkReason.RUN_OPERATION, 2, 5,
            MeasurementScope.ALL_TEST_SUITES, MeasurementTrust.SANDBOX_EXTERNAL, null);
    private final JavaSuitePlanner planner;
    private final JavaSuiteArchive archive;
    private final Judge0Client client;
    private final Judge0SuiteEvaluator evaluator;
    private final LongSupplier nanoTime;

    public JavaRunExecutor(JavaSuitePlanner planner, JavaSuiteArchive archive,
            Judge0Client client, Judge0SuiteEvaluator evaluator) {
        this(planner, archive, client, evaluator, System::nanoTime);
    }

    JavaRunExecutor(JavaSuitePlanner planner, JavaSuiteArchive archive,
            Judge0Client client, Judge0SuiteEvaluator evaluator, LongSupplier nanoTime) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.client = Objects.requireNonNull(client, "client");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public RunResult execute(RunRequest request) {
        return execute(request, Duration.ofNanos(DEADLINE_NANOS));
    }

    /** Admission supplies the deadline remaining after queue waiting. */
    public RunResult execute(RunRequest request, Duration timeLeft) {
        Objects.requireNonNull(timeLeft, "timeLeft");
        if (timeLeft.isZero() || timeLeft.isNegative()) {
            throw new RunDeadlineExceededException();
        }
        long budgetNanos = timeLeft.compareTo(Duration.ofNanos(DEADLINE_NANOS)) < 0
                ? timeLeft.toNanos() : DEADLINE_NANOS;
        long started = nanoTime.getAsLong();
        var plans = planner.prepare(request);
        var results = new ArrayList<TestResult>();
        var status = Verdict.ACCEPTED;
        double compilationCpuMs = 0;
        double compilationWallMs = 0;
        double runtimeCpuMs = 0;
        double runtimeWallMs = 0;
        Long peakMemoryBytes = null;
        boolean executed = false;

        for (int i = 0; i < plans.size(); i++) {
            remaining(started, budgetNanos);
            TestSuite suite = request.testSuites().get(i);
            int buildCpu = remainingMillis(10_000, compilationCpuMs);
            int buildWall = remainingMillis(20_000, compilationWallMs);
            if (buildCpu < 1 || buildWall < 1) {
                appendNotRun(results, suite);
                status = higherPriority(status, Verdict.COMPILATION_ERROR);
                continue;
            }
            int wall = remainingMillis(request.limits().wallTimeMs(), runtimeWallMs);
            int cpu = Math.min(remainingMillis(request.limits().cpuTimeMs(), runtimeCpuMs), wall);
            if (cpu < 1 || wall < 1) {
                appendNotRun(results, suite);
                status = higherPriority(status, Verdict.TIME_LIMIT_EXCEEDED);
                continue;
            }

            byte[] zip = archive.create(plans.get(i), buildCpu, buildWall);
            var limits = new ExecutionLimits(cpu, wall, request.limits().memoryLimitBytes());
            var token = client.submitProject(zip, limits, remaining(started, budgetNanos));
            var engine = client.awaitResult(token, remainingForActiveSubmission(started, budgetNanos, token));
            var report = client.getSuiteReport(token, remaining(started, budgetNanos));
            remaining(started, budgetNanos);
            if (!engine.token().equals(token) || !engine.status().finished() || report.compilation() == null) {
                throw new ExecutionUnavailableException();
            }

            compilationCpuMs += report.compilation().cpuTimeMs();
            compilationWallMs += report.compilation().wallTimeMs();
            var evaluation = evaluator.evaluate(suite, engine, report);
            results.addAll(evaluation.testResults());
            status = higherPriority(status, evaluation.status());
            if (compilationCpuMs > 10_000 || compilationWallMs > 20_000) {
                status = higherPriority(status, Verdict.COMPILATION_ERROR);
            }
            if (engine.status() != Judge0Status.COMPILATION_ERROR) {
                if (engine.cpuTimeMs() == null || engine.wallTimeMs() == null || engine.memoryBytes() == null) {
                    throw new ExecutionUnavailableException();
                }
                executed = true;
                runtimeCpuMs += engine.cpuTimeMs();
                runtimeWallMs += engine.wallTimeMs();
                peakMemoryBytes = peakMemoryBytes == null
                        ? engine.memoryBytes() : Math.max(peakMemoryBytes, engine.memoryBytes());
                if (runtimeCpuMs > request.limits().cpuTimeMs() || runtimeWallMs > request.limits().wallTimeMs()) {
                    status = higherPriority(status, Verdict.TIME_LIMIT_EXCEEDED);
                }
            }
        }

        remaining(started, budgetNanos);
        int passed = (int) results.stream().filter(result -> result.status() == TestVerdict.ACCEPTED).count();
        return new RunResult(status, passed, results.size(), results, null, null,
                executed ? runtimeCpuMs : null, peakMemoryBytes, NO_BENCHMARK,
                GradingIntegrity.HOST_TESTS_IN_PARTICIPANT_SANDBOX);
    }

    private Duration remaining(long started, long budgetNanos) {
        long remaining = budgetNanos - (nanoTime.getAsLong() - started);
        if (remaining <= 0) {
            throw new RunDeadlineExceededException();
        }
        return Duration.ofNanos(remaining);
    }

    private Duration remainingForActiveSubmission(long started, long budgetNanos, Judge0SubmissionToken token) {
        try {
            return remaining(started, budgetNanos);
        } catch (RunDeadlineExceededException exception) {
            throw new Judge0ClientException(Judge0ClientException.Reason.TIMEOUT, token);
        }
    }

    private static int remainingMillis(int limit, double used) {
        return (int) Math.floor(limit - used);
    }

    private static void appendNotRun(ArrayList<TestResult> results, TestSuite suite) {
        for (var testCase : suite.cases()) {
            results.add(new TestResult(testCase.testId(), suite.suiteId(), suite.visibility(),
                    TestVerdict.NOT_RUN, null, null, null, null, null));
        }
    }
}
