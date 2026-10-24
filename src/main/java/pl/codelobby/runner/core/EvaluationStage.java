package pl.codelobby.runner.core;

import java.util.List;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;

/** Validates reports and builds public results, including NOT_RUN cases for failed targets. */
public interface EvaluationStage {
    RunResult evaluate(RunRequest request, List<SuiteExecution> suites);
}
