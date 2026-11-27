package pl.codelobby.runner.core;

/** Missing or expired job without exposing its requested identifier. */
public final class EvaluationJobNotFoundException extends RuntimeException {
    public EvaluationJobNotFoundException() {
        super("Evaluation job not found");
    }
}
