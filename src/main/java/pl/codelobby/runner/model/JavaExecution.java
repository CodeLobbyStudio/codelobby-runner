package pl.codelobby.runner.model;

/** Fully qualified main class of the Host's Java test program. */
public record JavaExecution(String mainClass) implements ExecutionProfile {
}
