package pl.codelobby.runner.languages.java;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaCompiledSuiteArchiveTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final byte[] magic = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe};
    private final JavaCompiledSuiteArchive archive = new JavaCompiledSuiteArchive();

    @Test
    void buildsOnlyClassFilesAndRunScriptWithDeterministicBytes() throws Exception {
        var artifacts = read(snapshot(List.of(file("z/Other$1.class", magic), file("checks/Tests.class", magic))));
        byte[] zip = archive.create(artifacts, "checks.Tests");
        assertThat(archive.create(artifacts, "checks.Tests")).isEqualTo(zip);
        var paths = new ArrayList<String>();
        try (var stream = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                paths.add(entry.getName());
                byte[] content = stream.readAllBytes();
                if (entry.getName().equals("run")) {
                    assertThat(new String(content, StandardCharsets.UTF_8))
                            .contains("java-with-budget.sh java", "'checks.Tests'", "CODELOBBY_REPORT_PATH")
                            .doesNotContain("javac", "compiler", "CompileSuite.java");
                } else {
                    assertThat(content).isEqualTo(magic);
                }
            }
        }
        assertThat(paths).containsExactly(".codelobby/classes/checks/Tests.class", ".codelobby/classes/z/Other$1.class", "run");
        assertThat(paths).doesNotContain("compile", "compile.sh", "run.sh");
    }

    @Test
    void snapshotsTransportAndDoesNotExposeMutableByteArrays() throws Exception {
        byte[] bytes = snapshot(List.of(file("Tests.class", magic)));
        var artifacts = read(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        artifacts.copyClasses().get("Tests.class")[0] = 0;
        assertThat(artifacts.copyClasses().get("Tests.class")).isEqualTo(magic);
        assertThat(artifacts.fileCount()).isEqualTo(1);
        assertThat(artifacts.toString()).doesNotContain("Tests.class", "yv66vg");
        assertThat(archive.create(artifacts, "Tests")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../Secret.class", "/Secret.class", "C:/Secret.class", "a\\Secret.class",
            "a//Secret.class", "a/./Secret.class", "a/../Secret.class", ".codelobby/Main.class",
            "bad name.class", "ż.class", "Test.java", "compile", "a/Tests.class/run", "Tests.CLASS"})
    void rejectsUnsafeOrNonClassPaths(String path) throws Exception {
        reject(snapshot(List.of(file(path, magic))));
    }

    @Test
    void rejectsDuplicatePathsAndExcessiveDepthOrLength() throws Exception {
        reject(snapshot(List.of(file("Tests.class", magic), file("Tests.class", magic))));
        reject(snapshot(List.of(file("a/".repeat(32) + "Tests.class", magic))));
        reject(snapshot(List.of(file("a".repeat(235) + ".class", magic))));
    }

    @Test
    void requiresExactFieldsAndVersionAndRejectsDuplicateJsonKeysOrTrailingData() throws Exception {
        String valid = new String(snapshot(List.of(file("Tests.class", magic))), StandardCharsets.UTF_8);
        reject(valid.replace("\"version\":1", "\"version\":2").getBytes(StandardCharsets.UTF_8));
        reject(valid.replace("\"version\":1", "\"version\":\"1\"").getBytes(StandardCharsets.UTF_8));
        reject(valid.replace("\"version\":1", "\"version\":1,\"version\":1").getBytes(StandardCharsets.UTF_8));
        reject((valid + "{}").getBytes(StandardCharsets.UTF_8));
        reject(valid.replace("\"path\":", "\"unknown\":").getBytes(StandardCharsets.UTF_8));
        reject(snapshot(List.of(Map.of("path", "Tests.class", "contentBase64", "yv66vg==", "extra", "private"))));
        reject("{\"version\":1,\"files\":[],\"extra\":1}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidUtf8AndNullInputWithoutEchoingTransportBytes() throws Exception {
        reject(new byte[]{(byte) 0xff});
        assertThatThrownBy(() -> JavaCompiledArtifacts.read(null, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JavaCompiledArtifacts.read(new byte[1500001], "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiesDigestBeforeAcceptingAChangedSnapshot() throws Exception {
        byte[] bytes = snapshot(List.of(file("Tests.class", magic)));
        String hash = digest(bytes);
        bytes[bytes.length - 1] = ' ';
        assertThatThrownBy(() -> JavaCompiledArtifacts.read(bytes, hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JavaCompiledArtifacts.read(bytes, "wrong-hash"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("wrong-hash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"!invalid!", "yv66vg", "yv66vg==\n", "cHJpdmF0ZS1ieXRlcw==", ""})
    void rejectsNonCanonicalBase64OrInvalidClassMagic(String encoded) throws Exception {
        reject(snapshot(List.of(Map.of("path", "Tests.class", "contentBase64", encoded))));
    }

    @Test
    void enforcesFileCountAndByteBoundaries() throws Exception {
        var files = new ArrayList<Map<String, String>>();
        for (int i = 0; i < 256; i++) {
            files.add(file("C" + i + ".class", magic));
        }
        assertThat(read(snapshot(files)).fileCount()).isEqualTo(256);
        files.add(file("Extra.class", magic));
        reject(snapshot(files));
        reject(snapshot(List.of()));
        byte[] full = new byte[262144];
        System.arraycopy(magic, 0, full, 0, 4);
        files.clear();
        for (int i = 0; i < 4; i++) {
            files.add(file("Large" + i + ".class", full));
        }
        assertThat(read(snapshot(files)).fileCount()).isEqualTo(4);
        files.add(file("Extra.class", magic));
        reject(snapshot(files));
        reject(snapshot(List.of(file("Large.class", java.util.Arrays.copyOf(full, full.length + 1)))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Missing", "checks.Tests;command", "../Tests", "checks/Tests", "ż"})
    void requiresAnExistingSafeEntryClass(String main) throws Exception {
        var artifacts = read(snapshot(List.of(file("checks/Tests.class", magic))));
        assertThatThrownBy(() -> archive.create(artifacts, main)).isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, String> file(String path, byte[] content) {
        return Map.of("path", path, "contentBase64", Base64.getEncoder().encodeToString(content));
    }

    private byte[] snapshot(List<Map<String, String>> files) {
        return mapper.writeValueAsBytes(Map.of("version", 1, "files", files));
    }

    private JavaCompiledArtifacts read(byte[] bytes) throws Exception {
        return JavaCompiledArtifacts.read(bytes, digest(bytes));
    }

    private void reject(byte[] bytes) throws Exception {
        assertThatThrownBy(() -> JavaCompiledArtifacts.read(bytes, digest(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid compiled Java artifact snapshot");
    }

    private String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
