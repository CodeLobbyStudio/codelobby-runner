package pl.codelobby.runner.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import pl.codelobby.runner.model.SubmitRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Contract fingerprint for a validated payload. Never normalizes source contents or suite order. */
public final class SubmissionFingerprint {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SubmissionFingerprint() {
    }

    public static String compute(SubmitRequest request) {
        Objects.requireNonNull(request, "request");
        var input = MAPPER.valueToTree(request).asObject();
        input.remove("submissionId");
        sortPaths(input.get("taskFiles").asArray(), true);
        sortPaths(input.get("submissionFiles").asArray(), true);
        for (var suite : input.get("testSuites")) {
            sortPaths(suite.get("testFilePaths").asArray(), false);
        }
        byte[] canonical = MAPPER.writeValueAsBytes(sortedObjects(input));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void sortPaths(ArrayNode array, boolean fileObjects) {
        var values = new ArrayList<JsonNode>();
        array.forEach(values::add);
        values.sort(Comparator.comparing(value ->
                (fileObjects ? value.get("path").asString() : value.asString()).toLowerCase(Locale.ROOT)));
        array.removeAll();
        values.forEach(array::add);
    }

    private static JsonNode sortedObjects(JsonNode node) {
        if (node.isObject()) {
            var result = MAPPER.createObjectNode();
            node.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), sortedObjects(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode();
            node.forEach(value -> result.add(sortedObjects(value)));
            return result;
        }
        return node;
    }
}
