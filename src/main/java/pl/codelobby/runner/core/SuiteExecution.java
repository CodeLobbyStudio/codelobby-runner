package pl.codelobby.runner.core;

import java.util.Objects;

/** Evidence for evaluation: unsuccessful builds have no execution report. */
public record SuiteExecution(String suiteId, CompilationResult compilation, ExecutionReport execution) {

    public SuiteExecution {
        Objects.requireNonNull(suiteId, "suiteId");
        Objects.requireNonNull(compilation, "compilation");
        if (compilation.successful() != (execution != null)) {
            throw new IllegalArgumentException("Build and execution outcomes are inconsistent");
        }
    }
}
