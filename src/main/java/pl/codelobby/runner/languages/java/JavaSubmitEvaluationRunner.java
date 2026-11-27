package pl.codelobby.runner.languages.java;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import pl.codelobby.runner.core.ExecutionUnavailableException;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunDeadlineExceededException;
import pl.codelobby.runner.core.SubmitRequestValidator;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.Judge0CompilationUsage;

import static pl.codelobby.runner.core.SuiteEvaluation.higherPriority;

/** Evaluation phase only, before benchmarking. Admission and uncertain termination belong to its caller. */
public final class JavaSubmitEvaluationRunner {

    private static final Duration DEADLINE = Duration.ofSeconds(180);
    private final SubmitRequestValidator validator = new SubmitRequestValidator();
    private final SuiteOperation executor;
    private final LongSupplier nanoTime;

    public JavaSubmitEvaluationRunner(JavaSubmitSuiteExecutor executor) {
        this(executor, System::nanoTime);
    }

    JavaSubmitEvaluationRunner(JavaSubmitSuiteExecutor executor, LongSupplier nanoTime) {
        this((SuiteOperation) Objects.requireNonNull(executor, "executor")::execute, nanoTime);
    }

    private JavaSubmitEvaluationRunner(SuiteOperation executor, LongSupplier nanoTime) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    static JavaSubmitEvaluationRunner rounds(SuiteOperation executor, LongSupplier nanoTime) {
        return new JavaSubmitEvaluationRunner(executor, nanoTime);
    }

    @FunctionalInterface
    interface SuiteOperation {
        JavaSubmitSuiteExecutor.Outcome execute(SubmitRequest request, int index, ExecutionLimits limits,
                int buildCpu, int buildWall, Duration left);
    }

    @FunctionalInterface
    interface SuiteObserver {
        void evaluated(int index, JavaSubmitSuiteExecutor.Outcome outcome, Duration left);
    }

    /** Admission supplies time remaining after queue waiting. Evaluation never resets it per suite. */
    public Summary evaluate(SubmitRequest request, Duration timeLeft) {
        return evaluate(request, timeLeft, (index, outcome, left) -> {});
    }

    Summary evaluate(SubmitRequest request, Duration timeLeft, SuiteObserver observer) {
        if (timeLeft == null || timeLeft.isZero() || timeLeft.isNegative()) {
            throw new RunDeadlineExceededException();
        }
        long started = nanoTime.getAsLong();
        long budget = timeLeft.compareTo(DEADLINE) > 0 ? DEADLINE.toNanos() : timeLeft.toNanos();
        validator.validate(request);
        if (request.language() != Language.JAVA) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST,
                    "Java evaluation requires the Java language");
        }
        var results = new ArrayList<TestResult>();
        var status = Verdict.ACCEPTED;
        double buildCpu = 0;
        double buildWall = 0;
        double runtimeCpu = 0;
        double runtimeWall = 0;
        Long peakMemory = null;

        for (int index = 0; index < request.testSuites().size(); index++) {
            remaining(started, budget);
            var suite = request.testSuites().get(index);
            int compilationCpu = remainingMillis(10000, buildCpu);
            int compilationWall = remainingMillis(20000, buildWall);
            if (compilationCpu < 1 || compilationWall < 1) {
                appendNotRun(results, suite);
                status = higherPriority(status, Verdict.COMPILATION_ERROR);
                continue;
            }
            int wall = remainingMillis(request.limits().wallTimeMs(), runtimeWall);
            int cpu = Math.min(remainingMillis(request.limits().cpuTimeMs(), runtimeCpu), wall);
            if (cpu < 1 || wall < 1) {
                appendNotRun(results, suite);
                status = higherPriority(status, Verdict.TIME_LIMIT_EXCEEDED);
                continue;
            }

            var limits = new ExecutionLimits(cpu, wall, request.limits().memoryLimitBytes());
            var outcome = executor.execute(request, index, limits, compilationCpu, compilationWall,
                    remaining(started, budget));
            remaining(started, budget);
            validateOutcome(suite, outcome);
            observer.evaluated(index, outcome, remaining(started, budget));
            remaining(started, budget);
            buildCpu += outcome.compilation().cpuTimeMs();
            buildWall += outcome.compilation().wallTimeMs();
            results.addAll(outcome.evaluation().testResults());
            status = higherPriority(status, outcome.evaluation().status());
            if (buildCpu > 10000 || buildWall > 20000) {
                status = higherPriority(status, Verdict.COMPILATION_ERROR);
            }
            if (outcome.cpuTimeMs() != null) {
                runtimeCpu += outcome.cpuTimeMs();
                runtimeWall += outcome.wallTimeMs();
                peakMemory = peakMemory == null ? outcome.peakMemoryBytes() : Math.max(peakMemory, outcome.peakMemoryBytes());
                if (runtimeCpu > request.limits().cpuTimeMs() || runtimeWall > request.limits().wallTimeMs()) {
                    status = higherPriority(status, Verdict.TIME_LIMIT_EXCEEDED);
                }
                if (outcome.peakMemoryBytes() > request.limits().memoryLimitBytes()) {
                    status = higherPriority(status, Verdict.MEMORY_LIMIT_EXCEEDED);
                }
            }
        }
        remaining(started, budget);
        int passed = (int) results.stream().filter(result -> result.status() == TestVerdict.ACCEPTED).count();
        return new Summary(status, passed, results.size(), results, new Judge0CompilationUsage(buildCpu, buildWall),
                peakMemory == null ? null : runtimeCpu, peakMemory == null ? null : runtimeWall, peakMemory);
    }

    private static void validateOutcome(TestSuite suite, JavaSubmitSuiteExecutor.Outcome outcome) {
        if (outcome == null || outcome.evaluation().testResults().size() != suite.cases().size()) {
            throw new ExecutionUnavailableException();
        }
        for (int i = 0; i < suite.cases().size(); i++) {
            var result = outcome.evaluation().testResults().get(i);
            if (!suite.cases().get(i).testId().equals(result.testId()) || !suite.suiteId().equals(result.suiteId())
                    || result.visibility() != suite.visibility() || result.status() == null
                    || (outcome.evaluation().status() == Verdict.ACCEPTED && result.status() != TestVerdict.ACCEPTED)) {
                throw new ExecutionUnavailableException();
            }
        }
        boolean noMetrics = outcome.cpuTimeMs() == null && outcome.wallTimeMs() == null && outcome.peakMemoryBytes() == null;
        if (noMetrics) {
            if (outcome.evaluation().status() != Verdict.COMPILATION_ERROR
                    && outcome.evaluation().status() != Verdict.EXECUTION_ERROR) {
                throw new ExecutionUnavailableException();
            }
        } else if (outcome.cpuTimeMs() == null || outcome.wallTimeMs() == null || outcome.peakMemoryBytes() == null
                || !Double.isFinite(outcome.cpuTimeMs()) || outcome.cpuTimeMs() < 0
                || !Double.isFinite(outcome.wallTimeMs()) || outcome.wallTimeMs() < 0 || outcome.peakMemoryBytes() < 0) {
            throw new ExecutionUnavailableException();
        }
    }

    private Duration remaining(long started, long budget) {
        long left = budget - (nanoTime.getAsLong() - started);
        if (left <= 0) {
            throw new RunDeadlineExceededException();
        }
        return Duration.ofNanos(left);
    }

    private static int remainingMillis(int limit, double used) {
        return (int) Math.floor(limit - used);
    }

    private static void appendNotRun(List<TestResult> results, TestSuite suite) {
        for (var test : suite.cases()) {
            results.add(new TestResult(test.testId(), suite.suiteId(), suite.visibility(), TestVerdict.NOT_RUN,
                    null, null, null, null, null));
        }
    }

    /** Internal pre-benchmark summary, not an API EvaluationResult or a finished asynchronous job. */
    public record Summary(Verdict status, int passedTests, int totalTests, List<TestResult> testResults,
            Judge0CompilationUsage compilation, Double sandboxCpuTimeMs, Double sandboxWallTimeMs,
            Long sandboxPeakMemoryBytes) {

        public Summary {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(compilation, "compilation");
            testResults = List.copyOf(testResults);
        }

        @Override
        public String toString() {
            return "SubmitEvaluationSummary[status=" + status + ", passedTests=" + passedTests
                    + ", totalTests=" + totalTests + ", diagnostics=<redacted>]";
        }
    }
}
