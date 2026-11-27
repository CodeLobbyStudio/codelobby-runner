package pl.codelobby.runner.languages.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Restores verified class bytes through Judge0 project extraction, without a compiler phase. */
public final class JavaCompiledSuiteArchive {

    private static final Pattern MAIN_CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final String RUN = """
            #!/bin/bash
            set -euo pipefail
            export CODELOBBY_REPORT_PATH="$PWD/.codelobby/report.ndjson"
            : > "$CODELOBBY_REPORT_PATH"
            exec /usr/local/bin/java-with-budget.sh java -classpath .codelobby/classes '%s'
            """;

    public byte[] create(JavaCompiledArtifacts artifacts, String mainClass) {
        if (artifacts == null || mainClass == null || mainClass.length() > 240
                || !MAIN_CLASS.matcher(mainClass).matches()
                || !artifacts.contains(mainClass.replace('.', '/') + ".class")) {
            throw new IllegalArgumentException("Invalid compiled Java suite entry point");
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : artifacts.copyClasses().entrySet()) {
                write(zip, ".codelobby/classes/" + entry.getKey(), entry.getValue());
            }
            write(zip, "run", RUN.formatted(mainClass).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not build compiled Java suite archive");
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        var entry = new ZipEntry(path);
        entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
