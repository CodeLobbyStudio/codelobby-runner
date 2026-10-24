package pl.codelobby.runner.core;

/** Internal build outcome. artifactId is an opaque engine handle, not a host path or shell command. */
public record CompilationResult(boolean successful, String artifactId, String diagnostics) {

    public CompilationResult {
        if (successful ? artifactId == null || artifactId.isBlank() : artifactId != null) {
            throw new IllegalArgumentException("Invalid compilation outcome");
        }
    }
}
