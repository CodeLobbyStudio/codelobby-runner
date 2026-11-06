package pl.codelobby.runner.core;

import java.util.Objects;

import pl.codelobby.runner.model.FileType;

/** Internal in-memory source after overlays, not an API response or a file on disk. */
public record PreparedSourceFile(String path, FileType type, String content) {

    public PreparedSourceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
    }

    @Override
    public String toString() {
        return "PreparedSourceFile[type=" + type + ", path=<redacted>, content=<redacted>]";
    }
}
