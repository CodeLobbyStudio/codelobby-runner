package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionFingerprintTests {

    private final SubmitRequest original = JsonMapper.builder().build()
            .readValue(Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);

    @Test
    void matchesAnIndependentlyComputedCanonicalJsonSha256Vector() {
        // Python json.dumps with sorted keys, compact separators and unescaped UTF-8.
        assertThat(SubmissionFingerprint.compute(original))
                .isEqualTo("16689055d529934eeae00acffc50d4c2aea52f31ddef98c0cee6364889cbbca7");
    }

    @Test
    void excludesTheSubmissionIdAndIgnoresHostFileOrdering() {
        var files = new ArrayList<>(original.taskFiles());
        Collections.reverse(files);
        var expectedOrder = List.copyOf(files);
        var reordered = new SubmitRequest("another-id", original.taskVersionId(), original.language(), files,
                original.submissionFiles(), original.testSuites(), original.limits());
        assertThat(SubmissionFingerprint.compute(reordered)).isEqualTo(SubmissionFingerprint.compute(original));
        assertThat(files).isEqualTo(expectedOrder);
    }

    @Test
    void preservesSourceWhitespaceLineEndingsAndUnicodeExactly() {
        var fingerprints = new ArrayList<String>();
        for (String suffix : List.of("", " ", "\n", "\r\n", "// \u00e9\n", "// e\u0301\n")) {
            var files = new ArrayList<>(original.taskFiles());
            var file = files.getFirst();
            files.set(0, new TaskFile(file.path(), file.type(), file.content() + suffix));
            var changed = new SubmitRequest(original.submissionId(), original.taskVersionId(), original.language(),
                    files, original.submissionFiles(), original.testSuites(), original.limits());
            fingerprints.add(SubmissionFingerprint.compute(changed));
        }
        assertThat(fingerprints).doesNotHaveDuplicates();
    }

    @Test
    void preservesSuiteAndCaseOrder() {
        var suites = new ArrayList<>(original.testSuites());
        Collections.reverse(suites);
        assertThat(SubmissionFingerprint.compute(withSuites(suites)))
                .isNotEqualTo(SubmissionFingerprint.compute(original));
        var first = original.testSuites().getFirst();
        var cases = List.of(new TestCaseDefinition("public-1"), new TestCaseDefinition("public-2"));
        var forward = new TestSuite(first.suiteId(), first.visibility(), first.testFilePaths(), first.execution(), cases);
        var reversed = new TestSuite(first.suiteId(), first.visibility(), first.testFilePaths(), first.execution(),
                List.of(cases.get(1), cases.get(0)));
        assertThat(SubmissionFingerprint.compute(withSuites(List.of(forward, original.testSuites().get(1)))))
                .isNotEqualTo(SubmissionFingerprint.compute(withSuites(List.of(reversed, original.testSuites().get(1)))));
    }

    @Test
    void ignoresOverrideAndSuiteFileOrderingWithoutMutatingTheRequests() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("src/Other.java", FileType.EDITABLE, "class Other {}"));
        files.add(new TaskFile("tests/OtherTests.java", FileType.VISIBLE_TEST, "class OtherTests {}"));
        var overrides = new ArrayList<>(original.submissionFiles());
        overrides.add(new SubmissionFile("src/Other.java", "class Other { int value = 1; }"));
        var first = original.testSuites().getFirst();
        var paths = List.of("tests/PublicTests.java", "tests/OtherTests.java");
        var suite = new TestSuite(first.suiteId(), first.visibility(), paths, first.execution(), first.cases());
        var a = new SubmitRequest("first", original.taskVersionId(), original.language(), files, overrides,
                List.of(suite, original.testSuites().get(1)), original.limits());
        var reversedOverrides = new ArrayList<>(overrides);
        Collections.reverse(reversedOverrides);
        var reordered = new TestSuite(first.suiteId(), first.visibility(), List.of(paths.get(1), paths.get(0)),
                first.execution(), first.cases());
        var b = new SubmitRequest("second", original.taskVersionId(), original.language(), files, reversedOverrides,
                List.of(reordered, original.testSuites().get(1)), original.limits());
        assertThat(SubmissionFingerprint.compute(a)).isEqualTo(SubmissionFingerprint.compute(b));
        assertThat(a.testSuites().getFirst().testFilePaths()).containsExactlyElementsOf(paths);
        assertThat(b.submissionFiles()).containsExactlyElementsOf(reversedOverrides);
    }

    private SubmitRequest withSuites(List<TestSuite> suites) {
        return new SubmitRequest(original.submissionId(), original.taskVersionId(), original.language(),
                original.taskFiles(), original.submissionFiles(), suites, original.limits());
    }
}
