package pl.codelobby.runner.sandbox.judge0;

/** Bounded private capture. Integrity and class-path validation belong to its consumer. */
public record Judge0ArtifactSnapshot(byte[] content, String sha256) {
    public Judge0ArtifactSnapshot {
        if (content == null || content.length == 0 || content.length > 1500000
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid private artifact snapshot");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() { return content.clone(); }

    @Override
    public String toString() { return "Judge0ArtifactSnapshot[<redacted>]"; }
}
