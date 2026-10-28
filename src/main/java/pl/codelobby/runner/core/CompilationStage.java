package pl.codelobby.runner.core;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.TestSuite;

/** Prepares one suite target in an isolated execution service, never on the application host. */
public interface CompilationStage {
    CompilationResult compile(RunRequest request, TestSuite suite);
}
