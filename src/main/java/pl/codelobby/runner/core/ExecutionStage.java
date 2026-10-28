package pl.codelobby.runner.core;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.TestSuite;

/** Runs a successfully prepared suite through an isolated execution service. */
public interface ExecutionStage {
    ExecutionReport run(RunRequest request, TestSuite suite, CompilationResult compilation);
}
