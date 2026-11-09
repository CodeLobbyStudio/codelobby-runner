package pl.codelobby.runner.languages.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import pl.codelobby.runner.model.FileType;

/** Creates the in-memory Judge0 multi-file payload for one validated public Java suite. */
public final class JavaSuiteArchive {

    private static final Pattern SOURCE_PATH =
            Pattern.compile("(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.java");
    private static final Pattern MAIN_CLASS =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final String COMPILE = """
            #!/bin/bash
            set -euo pipefail
            mkdir -p .codelobby/classes
            sources=(
            %s)
            exec /usr/local/bin/java-with-budget.sh javac --release 21 -encoding UTF-8 -proc:none \\
                -classpath .codelobby/classes -d .codelobby/classes "${sources[@]}"
            """;
    private static final String RUN = """
            #!/bin/bash
            set -euo pipefail
            export CODELOBBY_REPORT_PATH="$PWD/.codelobby/report.ndjson"
            : > "$CODELOBBY_REPORT_PATH"
            exec /usr/local/bin/java-with-budget.sh java -classpath .codelobby/classes '%s'
            """;

    public byte[] create(JavaSuitePlan plan) {
        if (!MAIN_CLASS.matcher(plan.mainClass()).matches() || plan.files().isEmpty()) {
            throw new IllegalArgumentException("Invalid Java suite archive input");
        }

        var bytes = new ByteArrayOutputStream();
        var sources = new StringBuilder();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : plan.files()) {
                if (!SOURCE_PATH.matcher(file.path()).matches() || file.type() == FileType.HIDDEN_TEST) {
                    throw new IllegalArgumentException("Invalid Java suite archive source");
                }
                String entry = "sources/" + file.path();
                writeEntry(zip, entry, file.content());
                sources.append("    '").append(entry).append("'\n");
            }
            writeEntry(zip, "compile", COMPILE.formatted(sources));
            writeEntry(zip, "run", RUN.formatted(plan.mainClass()));
        } catch (IOException exception) {
            // ZIP errors can include entry names. Keep them out of runner diagnostics.
            throw new IllegalStateException("Could not build Java suite archive");
        }
        return bytes.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
