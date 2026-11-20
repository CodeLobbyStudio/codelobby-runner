package pl.codelobby.runner.core;

import java.time.Duration;

import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.SubmitRequest;

/** Enforces the supplied budget and returns only after verified evaluation and cleanup. */
@FunctionalInterface
public interface EvaluationExecutor {

    EvaluationResult evaluate(SubmitRequest request, Duration remainingBudget);
}
