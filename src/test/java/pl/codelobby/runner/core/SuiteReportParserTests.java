package pl.codelobby.runner.core;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.TestVerdict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuiteReportParserTests {

    private final SuiteReportParser parser = new SuiteReportParser();

    @Test
    void readsOrderedCasesWithArbitraryDiagnosticsAndContinuesAfterAWrongAnswer() {
        var report = """
                {"testId":"first","status":"WRONG_ANSWER","input":{"text":"żółć","items":[1,true,null]},"expectedOutput":[2,3],"actualOutput":{"value":false}}
                {"testId":"second","status":"ACCEPTED"}
                {"testId":"third","status":"RUNTIME_ERROR","input":null}
                """;

        var results = parser.parse(utf8(report), List.of("first", "second", "third"));

        assertThat(results).extracting(ReportedTestCase::testId).containsExactly("first", "second", "third");
        assertThat(results).extracting(ReportedTestCase::status)
                .containsExactly(TestVerdict.WRONG_ANSWER, TestVerdict.ACCEPTED, TestVerdict.RUNTIME_ERROR);
        assertThat(results.getFirst().input().get("text").asString()).isEqualTo("żółć");
        assertThat(results.getFirst().expectedOutput().toString()).isEqualTo("[2,3]");
        assertThat(results.getFirst().actualOutput().get("value").asBoolean()).isFalse();
        assertThat(results.get(1).input()).isNull();
        assertThat(results.get(1).expectedOutput()).isNull();
        assertThat(results.get(1).actualOutput()).isNull();
        assertThat(results.get(2).input()).isNull();
        assertThatThrownBy(() -> results.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(results.getFirst().toString()).doesNotContain("żółć", "first");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\r\n"})
    void acceptsOptionalFinalLineEnding(String ending) {
        assertThat(parser.parse(utf8(record("first") + ending), List.of("first"))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "\n", " ", "null", "[]", "{}",
            "{\"testId\":\"first\"}",
            "{\"testId\":\"first\",\"status\":null}",
            "{\"testId\":1,\"status\":\"ACCEPTED\"}",
            "{\"testId\":\"first\",\"status\":3}",
            "{\"testId\":\"first\",\"status\":\"NOT_RUN\"}",
            "{\"testId\":\"first\",\"status\":\"TIME_LIMIT_EXCEEDED\"}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"suiteId\":\"injected\"}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"stdout\":\"private\"}",
            "{\"testId\":\"first\",\"testId\":\"first\",\"status\":\"ACCEPTED\"}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"input\":{\"key\":1,\"key\":2}}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"input\":[NaN]}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"input\":{\"value\":1e9999}}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\"} {}",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\"",
            "{\"testId\":\"first\",\"status\":\"ACCEPTED\",}"
    })
    void rejectsMalformedRecordsWithSafeDiagnostics(String report) {
        assertFailure(utf8(report), List.of("first"), TestVerdict.EXECUTION_ERROR);
    }

    @Test
    void rejectsMissingDuplicateReorderedUnknownAndExtraCases() {
        var expected = List.of("first", "second");
        for (var report : List.of(
                record("first"),
                record("first") + "\n" + record("first"),
                record("second") + "\n" + record("first"),
                record("other") + "\n" + record("second"),
                record("first") + "\n" + record("second") + "\n" + record("extra"),
                record("first") + "\n\n" + record("second"))) {
            assertFailure(utf8(report), expected, TestVerdict.EXECUTION_ERROR);
        }
    }

    @Test
    void acceptsCrLfBetweenRecords() {
        assertThat(parser.parse(utf8(record("first") + "\r\n" + record("second") + "\r\n"),
                List.of("first", "second"))).hasSize(2);
    }

    @Test
    void rejectsInvalidUtf8AndAlternateByteEncodings() {
        var prefix = utf8("{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"input\":\"");
        var suffix = utf8("\"}");
        var malformed = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0xff;
        System.arraycopy(suffix, 0, malformed, prefix.length + 1, suffix.length);
        assertFailure(malformed, List.of("first"), TestVerdict.EXECUTION_ERROR);
        assertFailure(record("first").getBytes(StandardCharsets.UTF_16BE),
                List.of("first"), TestVerdict.EXECUTION_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "expectedOutput", "actualOutput"})
    void enforcesTheRawUtf8LimitForEachDiagnosticValue(String field) {
        String prefix = "{\"testId\":\"first\",\"status\":\"ACCEPTED\",\"" + field + "\":\"";
        String atLimit = prefix + "ż".repeat(49_999) + "\"}";
        assertThat(parser.parse(utf8(atLimit), List.of("first"))).hasSize(1);
        assertFailure(utf8(prefix + "ż".repeat(50_000) + "\"}"), List.of("first"),
                TestVerdict.OUTPUT_LIMIT_EXCEEDED);

        // The raw escaped representation also counts towards the value's byte limit.
        assertFailure(utf8(prefix + "\\u0061".repeat(16_667) + "\"}"), List.of("first"),
                TestVerdict.OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void enforcesTheLineAndReportByteLimitsIncludingWhitespace() {
        String line = record("first");
        assertThat(parser.parse(utf8(line + " ".repeat(310_000 - line.length())), List.of("first"))).hasSize(1);
        assertFailure(utf8(line + " ".repeat(310_001 - line.length())), List.of("first"),
                TestVerdict.OUTPUT_LIMIT_EXCEEDED);

        StringBuilder report = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String entry = record("case-" + i);
            report.append(entry).append(" ".repeat(249_999 - entry.length())).append('\n');
        }
        var ids = List.of("case-0", "case-1", "case-2", "case-3");
        assertThat(parser.parse(utf8(report.toString()), ids)).hasSize(4);
        assertFailure(utf8(report + " "), ids, TestVerdict.OUTPUT_LIMIT_EXCEEDED);
    }

    private void assertFailure(byte[] report, List<String> ids, TestVerdict verdict) {
        assertThatThrownBy(() -> parser.parse(report, ids))
                .isInstanceOfSatisfying(SuiteReportException.class,
                        exception -> assertThat(exception.verdict()).isEqualTo(verdict))
                .hasMessage("Invalid suite report")
                .hasNoCause();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String record(String id) {
        return "{\"testId\":\"" + id + "\",\"status\":\"ACCEPTED\"}";
    }
}
