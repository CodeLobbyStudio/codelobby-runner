package pl.codelobby.runner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;

/** Stage ordering only; engine integration, aggregate budgets and cleanup are not implemented here. */
public final class StagedSubmissionExecutor implements SubmissionExecutor {

    private final CompilationStage compiler;
    private final ExecutionStage runner;
    private final EvaluationStage evaluator;

    public StagedSubmissionExecutor(CompilationStage compiler, ExecutionStage runner, EvaluationStage evaluator) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public RunResult execute(RunRequest request) {
        Objects.requireNonNull(request, "request");
        var outcomes = new ArrayList<SuiteExecution>();
        for (var suite : request.testSuites()) {
            var compilation = compiler.compile(request, suite);
            if (compilation == null) {
                throw new IllegalStateException("Compilation stage did not return a result");
            }
            ExecutionReport execution = null;
            if (compilation.successful()) {
                execution = runner.run(request, suite, compilation);
                if (execution == null) {
                    throw new IllegalStateException("Execution stage did not return a report");
                }
            }
            outcomes.add(new SuiteExecution(suite.suiteId(), compilation, execution));
        }
        var result = evaluator.evaluate(request, List.copyOf(outcomes));
        if (result == null) {
            throw new IllegalStateException("Evaluation stage did not return a result");
        }
        return result;
    }
}
