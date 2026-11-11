package pl.codelobby.runner.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.codelobby.runner.model.TestVerdict;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates CODELOBBY_NDJSON_V1 records against the declared case order. */
public final class SuiteReportParser {

    private static final int MAX_REPORT_BYTES = 1_000_000;
    private static final int MAX_LINE_BYTES = 310_000;
    private static final int MAX_VALUE_BYTES = 100_000;
    private static final Set<String> FIELDS =
            Set.of("testId", "status", "input", "expectedOutput", "actualOutput");
    private static final Set<String> STATUSES = Set.of("ACCEPTED", "WRONG_ANSWER", "RUNTIME_ERROR");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Requires a complete report when execution finished successfully. */
    public List<ReportedTestCase> parse(byte[] report, List<String> expectedTestIds) {
        return parse(report, expectedTestIds, false);
    }

    /** Retains only the valid ordered prefix after a verified fatal engine outcome. */
    public List<ReportedTestCase> parseTerminated(byte[] report, List<String> expectedTestIds) {
        return parse(report, expectedTestIds, true);
    }

    private List<ReportedTestCase> parse(byte[] report, List<String> expectedTestIds, boolean terminated) {
        Objects.requireNonNull(report, "report");
        var expected = List.copyOf(expectedTestIds);
        if (report.length > MAX_REPORT_BYTES) {
            throw oversized();
        }
        var results = new ArrayList<ReportedTestCase>();
        int start = 0;
        for (int end = 0; end <= report.length; end++) {
            if (end != report.length && report[end] != '\n') {
                continue;
            }
            // One final LF is optional. Empty records elsewhere are invalid.
            if (end == report.length && start == end) {
                break;
            }
            if (end - start > MAX_LINE_BYTES) {
                throw oversized();
            }
            try {
                if (results.size() >= expected.size()) {
                    throw invalid();
                }
                var result = parseLine(report, start, end - start);
                if (!result.testId().equals(expected.get(results.size()))) {
                    throw invalid();
                }
                results.add(result);
            } catch (SuiteReportException exception) {
                if (terminated && exception.verdict() == TestVerdict.EXECUTION_ERROR) {
                    return List.copyOf(results);
                }
                throw exception;
            }
            start = end + 1;
        }
        if (!terminated && (results.size() != expected.size() || results.isEmpty())) {
            throw invalid();
        }
        return List.copyOf(results);
    }

    private ReportedTestCase parseLine(byte[] bytes, int offset, int length) {
        try {
            // Validate records independently to preserve a prefix before a truncated UTF-8 sequence.
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length));
        } catch (CharacterCodingException exception) {
            throw invalid();
        }
        int first = offset;
        while (first < offset + length && (bytes[first] == ' ' || bytes[first] == '\t' || bytes[first] == '\r')) {
            first++;
        }
        if (first == offset + length || bytes[first] != '{') {
            throw invalid();
        }
        try (var parser = mapper.createParser(bytes, offset, length)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid();
            }
            var root = mapper.createObjectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME || !FIELDS.contains(parser.currentName())) {
                    throw invalid();
                }
                String name = parser.currentName();
                if (parser.nextToken() == null) {
                    throw invalid();
                }
                long start = parser.currentTokenLocation().getByteOffset();
                JsonNode value = mapper.readTree(parser);
                long size = parser.currentLocation().getByteOffset() - start;
                if (!name.equals("testId") && !name.equals("status") && size > MAX_VALUE_BYTES) {
                    throw oversized();
                }
                validateNumbers(value);
                root.set(name, value);
            }
            if (parser.nextToken() != null || !root.has("testId") || !root.get("testId").isString()
                    || !root.has("status") || !root.get("status").isString()
                    || !STATUSES.contains(root.get("status").asString())) {
                throw invalid();
            }
            return new ReportedTestCase(root.get("testId").asString(),
                    TestVerdict.valueOf(root.get("status").asString()),
                    nullable(root.get("input")), nullable(root.get("expectedOutput")),
                    nullable(root.get("actualOutput")));
        } catch (JacksonException exception) {
            // Parser exceptions may contain fragments of test data.
            throw invalid();
        }
    }

    private static void validateNumbers(JsonNode value) {
        var pending = new ArrayDeque<JsonNode>();
        pending.add(value);
        while (!pending.isEmpty()) {
            var node = pending.removeLast();
            if (node.isFloatingPointNumber() && !Double.isFinite(node.asDouble())) {
                throw invalid();
            }
            pending.addAll(node.values());
        }
    }

    private static JsonNode nullable(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }

    private static SuiteReportException invalid() {
        return new SuiteReportException(TestVerdict.EXECUTION_ERROR);
    }

    private static SuiteReportException oversized() {
        return new SuiteReportException(TestVerdict.OUTPUT_LIMIT_EXCEEDED);
    }
}
