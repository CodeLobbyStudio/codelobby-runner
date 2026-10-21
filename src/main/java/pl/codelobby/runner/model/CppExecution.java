package pl.codelobby.runner.model;

/** Relative path of the Host's C++ test entry source. */
public record CppExecution(String mainSourcePath) implements ExecutionProfile {
}
