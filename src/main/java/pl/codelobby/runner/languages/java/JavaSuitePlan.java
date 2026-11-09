package pl.codelobby.runner.languages.java;

import java.util.List;
import java.util.Objects;

import pl.codelobby.runner.core.PreparedSourceFile;

/** Immutable, internal in-memory input for one Java suite, not a sandbox or API result. */
public record JavaSuitePlan(
        String suiteId,
        String mainClass,
        List<PreparedSourceFile> files,
        List<String> testIds) {

    public JavaSuitePlan {
        Objects.requireNonNull(suiteId, "suiteId");
        Objects.requireNonNull(mainClass, "mainClass");
        files = List.copyOf(files);
        testIds = List.copyOf(testIds);
    }

    @Override
    public String toString() {
        return "JavaSuitePlan[suiteId=" + suiteId + ", mainClass=<redacted>, fileCount=" + files.size()
                + ", testCount=" + testIds.size() + "]";
    }
}
