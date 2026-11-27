package pl.codelobby.runner.languages.java;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded immutable class bytes from a private capture. SHA-256 checks integrity, not origin or request ownership. */
public final class JavaCompiledArtifacts {

    private static final int MAX_SNAPSHOT_BYTES = 1500000;
    private static final int MAX_CLASS_BYTES = 262144;
    private static final int MAX_TOTAL_BYTES = 1048576;
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CLASS_PATH = Pattern.compile(
            "(?:[A-Za-z0-9_$-]+/)*[A-Za-z0-9_$-]+\\.class");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final Map<String, byte[]> classes;

    private JavaCompiledArtifacts(Map<String, byte[]> classes) {
        this.classes = classes;
    }

    public static JavaCompiledArtifacts read(byte[] snapshot, String expectedSha256) {
        if (snapshot == null || snapshot.length == 0 || snapshot.length > MAX_SNAPSHOT_BYTES
                || expectedSha256 == null || !DIGEST.matcher(expectedSha256).matches()) {
            throw invalid();
        }
        // Snapshot the transport buffer before checking or parsing it.
        byte[] bytes = snapshot.clone();
        if (!MessageDigest.isEqual(sha256(bytes), HexFormat.of().parseHex(expectedSha256))) {
            throw invalid();
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var root = MAPPER.readTree(json);
            if (!root.isObject() || root.size() != 2 || root.get("version") == null
                    || !root.get("version").isInt() || root.get("version").intValue() != 1
                    || root.get("files") == null || !root.get("files").isArray()
                    || root.get("files").isEmpty() || root.get("files").size() > 256) {
                throw invalid();
            }
            Map<String, byte[]> classes = new TreeMap<>();
            int totalBytes = 0;
            for (var file : root.get("files")) {
                if (!file.isObject() || file.size() != 2 || file.get("path") == null || !file.get("path").isString()
                        || file.get("contentBase64") == null || !file.get("contentBase64").isString()) {
                    throw invalid();
                }
                String path = file.get("path").asString();
                String encoded = file.get("contentBase64").asString();
                if (path.length() > 240 || path.chars().filter(value -> value == '/').count() > 31
                        || !CLASS_PATH.matcher(path).matches() || classes.containsKey(path)
                        || encoded.length() > 4 * ((MAX_CLASS_BYTES + 2) / 3)) {
                    throw invalid();
                }
                byte[] content = Base64.getDecoder().decode(encoded);
                if (content.length < 4 || content.length > MAX_CLASS_BYTES
                        || !Base64.getEncoder().encodeToString(content).equals(encoded)
                        || content[0] != (byte) 0xca || content[1] != (byte) 0xfe
                        || content[2] != (byte) 0xba || content[3] != (byte) 0xbe) {
                    throw invalid();
                }
                totalBytes += content.length;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw invalid();
                }
                classes.put(path, content);
            }
            return new JavaCompiledArtifacts(classes);
        } catch (Exception exception) {
            // Parser and decoding exceptions can contain private paths or bytecode.
            throw invalid();
        }
    }

    public int fileCount() {
        return classes.size();
    }

    public int byteCount() {
        return classes.values().stream().mapToInt(bytes -> bytes.length).sum();
    }

    boolean contains(String path) {
        return classes.containsKey(path);
    }

    /** Give the archive builder copies, without exposing mutable internal byte arrays. */
    Map<String, byte[]> copyClasses() {
        Map<String, byte[]> copy = new TreeMap<>();
        classes.forEach((path, bytes) -> copy.put(path, bytes.clone()));
        return copy;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid compiled Java artifact snapshot");
    }

    @Override
    public String toString() {
        return "JavaCompiledArtifacts[fileCount=" + classes.size() + ", contents=<redacted>]";
    }
}
