package pl.codelobby.runner.model;

/** An authoritative Host file, including its role and original contents. */
public record TaskFile(String path, FileType type, String content) {
}
