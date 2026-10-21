package pl.codelobby.runner.model;

/** Relative path of the Host's Python test script. */
public record PythonExecution(String scriptPath) implements ExecutionProfile {
}
