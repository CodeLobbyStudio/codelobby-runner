package pl.codelobby.runner.languages.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.PreparedSourceFile;
import pl.codelobby.runner.model.FileType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSuiteArchiveTests {

    private final JavaSuiteArchive archive = new JavaSuiteArchive();

    @Test
    void packagesSourcesAndTrustedScriptsWithStableEntries() throws IOException {
        var plan = plan("tests.Public$Nested", List.of(
                new PreparedSourceFile("src/Solution.java", FileType.EDITABLE, "// żółć"),
                new PreparedSourceFile("-helpers/Helper.java", FileType.READ_ONLY, "// helper"),
                new PreparedSourceFile("tests/Public.java", FileType.VISIBLE_TEST, "// host test")));

        byte[] zip = archive.create(plan);
        Map<String, String> entries = unpack(zip);

        assertThat(archive.create(plan)).isEqualTo(zip);
        assertThat(entries.keySet()).containsExactly(
                "sources/src/Solution.java", "sources/-helpers/Helper.java", "sources/tests/Public.java",
                "compile", "run");
        assertThat(entries).containsEntry("sources/src/Solution.java", "// żółć")
                .containsEntry("sources/-helpers/Helper.java", "// helper")
                .containsEntry("sources/tests/Public.java", "// host test");
        assertThat(entries.get("compile")).contains(
                "'sources/src/Solution.java'", "'sources/-helpers/Helper.java'", "'sources/tests/Public.java'",
                "/usr/local/bin/java-with-budget.sh javac --release 21", "-proc:none", "\"${sources[@]}\"");
        assertThat(entries.get("run")).contains(
                "CODELOBBY_REPORT_PATH=\"$PWD/.codelobby/report.ndjson\"",
                ": > \"$CODELOBBY_REPORT_PATH\"",
                "/usr/local/bin/java-with-budget.sh java -classpath .codelobby/classes 'tests.Public$Nested'");
    }

    @Test
    void packagesTheRemainingCompilerBudgetForTheWorker() throws IOException {
        var plan = plan("Tests", List.of(new PreparedSourceFile("Tests.java", FileType.VISIBLE_TEST, "")));
        assertThat(unpack(archive.create(plan, 1234, 5678)).get("compile"))
                .contains("# CODELOBBY_BUILD_LIMITS 1234 5678");
    }

    @ParameterizedTest
    @CsvSource({"0,20000", "10001,20000", "10000,0", "10000,20001"})
    void rejectsInvalidCompilationBudgets(int cpu, int wall) {
        var plan = plan("Tests", List.of(new PreparedSourceFile("Tests.java", FileType.VISIBLE_TEST, "")));
        assertThatThrownBy(() -> archive.create(plan, cpu, wall)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../Outside.java", "/Absolute.java", "src/Bad'Name.java", "src/resource.txt"})
    void rejectsUnsafeOrUnsupportedPaths(String path) {
        var plan = plan("tests.Public", List.of(new PreparedSourceFile(path, FileType.EDITABLE, "")));

        assertThatThrownBy(() -> archive.create(plan)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Java suite archive source");
    }

    @Test
    void rejectsHiddenFiles() {
        var plan = plan("tests.Public",
                List.of(new PreparedSourceFile("tests/Hidden.java", FileType.HIDDEN_TEST, "secret")));

        assertThatThrownBy(() -> archive.create(plan)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Java suite archive source");
    }

    @Test
    void rejectsAnInvalidMainClassBeforeGeneratingTheScript() {
        var plan = plan("tests.Public' injected",
                List.of(new PreparedSourceFile("tests/Public.java", FileType.VISIBLE_TEST, "")));

        assertThatThrownBy(() -> archive.create(plan)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Java suite archive input");
    }

    private static JavaSuitePlan plan(String mainClass, List<PreparedSourceFile> files) {
        return new JavaSuitePlan("public-suite", mainClass, files, List.of("public-1"));
    }

    private static Map<String, String> unpack(byte[] bytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
