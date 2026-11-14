package pl.codelobby.runner.languages.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.RunSubmissionUseCase;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResult;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0Properties;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_LOCAL_JUDGE0", matches = "true")
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JUDGE0_REPORTS", matches = "true")
class JavaRunExecutorIntegrationTests {

    @ParameterizedTest
    @ValueSource(ints = {42, 41})
    void evaluatesTwoIndependentSuitesAndParticipantOverrides(int answer) {
        var request = request("class Solution { static int answer() { return " + answer + "; } }");

        var result = execute(request);

        assertThat(result.status()).isEqualTo(answer == 42 ? Verdict.ACCEPTED : Verdict.WRONG_ANSWER);
        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.passedTests()).isEqualTo(answer == 42 ? 2 : 0);
        assertThat(result.testResults()).extracting(TestResult::testId).containsExactly("case-1", "case-2");
        assertThat(result.sandboxCpuTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.sandboxPeakMemoryBytes()).isNotNull().isGreaterThan(0);
    }

    @Test
    void aggregatesCompilationFailuresWithoutExposingTestSources() {
        var result = execute(request("class Solution { static int answer() { return; } }"));

        assertThat(result.status()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.passedTests()).isZero();
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
        assertThat(result.compileOutput()).isNull();
        assertThat(result.sandboxCpuTimeMs()).isNull();
    }

    @Test
    void stopsRemainingSuitesWhenTheRequestCpuBudgetIsExhausted() {
        var original = request("class Solution { static int answer() { while (true) {} } }");
        var request = new RunRequest(original.requestId(), original.taskVersionId(), original.language(),
                original.taskFiles(), original.submissionFiles(), original.testSuites(),
                new ExecutionLimits(200, 1000, 128_000_000));

        var result = execute(request);

        assertThat(result.status()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(result.passedTests()).isZero();
        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-class", "missing-main", "nonstatic-main", "wrong-return", "host-syntax",
            "participant-entry"})
    void rejectsBrokenHostConfigurationAsAProcessingFailure(String failure) {
        var original = request("class Solution { static int answer() { return 42; } }");
        String source = switch (failure) {
            case "missing-main" -> "public class First {}";
            case "nonstatic-main" -> "public class First { public void main(String[] args) {} }";
            case "wrong-return" -> "public class First { public static int main(String[] args) { return 0; } }";
            case "host-syntax" -> "public class First { syntax error }";
            default -> hostSource("First", "case-1");
        };
        String main = switch (failure) {
            case "missing-class" -> "Missing";
            case "participant-entry" -> "Solution";
            default -> "First";
        };
        var suite = new TestSuite("suite-1", TestVisibility.PUBLIC, List.of("tests/First.java"),
                new JavaExecution(main), List.of(new TestCaseDefinition("case-1")));
        var request = singleSuite(original, source, suite);
        var result = execute(request);

        assertThat(result.status()).isEqualTo(Verdict.EXECUTION_ERROR);
        assertThat(result.passedTests()).isZero();
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(TestVerdict.NOT_RUN);
        assertThat(result.compileOutput()).isNull();
        assertThat(result.message()).isNull();
    }

    @Test
    void acceptsInheritedHostMainWithoutRunningStaticInitializersDuringCompilation() {
        var original = request("class Solution { static int answer() { return 42; } }");
        String source = hostSource("First", "case-1").replace("public class First", "class Base")
                + " public class First extends Base { static { if (true) throw new RuntimeException(); } }";
        var suite = new TestSuite("suite-1", TestVisibility.PUBLIC, List.of("tests/First.java"),
                new JavaExecution("First"), List.of(new TestCaseDefinition("case-1")));
        var result = execute(singleSuite(original, source, suite));
        // A static initializer failure is runtime evidence, not a compile-phase configuration error.
        assertThat(result.status()).isEqualTo(Verdict.RUNTIME_ERROR);
    }

    @ParameterizedTest
    @CsvSource({"0.30000000000000004,b;a,ACCEPTED,2", "0.3,a;b,ACCEPTED,2", "0.31,b;a,WRONG_ANSWER,1"})
    void preservesHostDefinedNumericToleranceAndAlternativeAcceptedAnswers(
            String value, String alternative, Verdict expected, int passed) {
        var original = request("class Solution { static double answer() { return " + value
                + "; } static String alternative() { return \"" + alternative + "\"; } }");
        String source = """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class First {
                    public static void main(String[] args) throws Exception {
                        double actual = Solution.answer();
                        boolean accepted = Math.abs(actual - 0.3) <= 0.000001;
                        String status = accepted ? "ACCEPTED" : "WRONG_ANSWER";
                        String report = "{\\"testId\\":\\"case-1\\",\\"status\\":\\"" + status
                            + "\\",\\"expectedOutput\\":0.3,\\"actualOutput\\":" + actual + "}\\n";
                        // Two different strings are also valid for an order-independent task.
                        String alternative = Solution.alternative();
                        boolean valid = java.util.Set.of("a;b", "b;a").contains(alternative);
                        report += "{\\"testId\\":\\"alternative\\",\\"status\\":\\""
                            + (valid ? "ACCEPTED" : "WRONG_ANSWER")
                            + "\\",\\"expectedOutput\\":\\"a;b\\",\\"actualOutput\\":\\"" + alternative + "\\"}\\n";
                        Files.writeString(Path.of(System.getenv("CODELOBBY_REPORT_PATH")), report);
                    }
                }
                """;
        var suite = new TestSuite("suite-1", TestVisibility.PUBLIC, List.of("tests/First.java"),
                new JavaExecution("First"), List.of(new TestCaseDefinition("case-1"),
                        new TestCaseDefinition("alternative")));
        var result = execute(singleSuite(original, source, suite));
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.passedTests()).isEqualTo(passed);
        assertThat(result.testResults().getLast().actualOutput().asString()).isEqualTo(alternative);
        assertThat(result.testResults().getLast().expectedOutput().asString()).isEqualTo("a;b");
    }

    @Test
    void participantRuntimeCannotForgeACompilePhaseConfigurationFailure() {
        var original = request("""
                class Solution {
                    static int answer() throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(".codelobby/configuration-error"),
                                "INVALID_HOST_TEST_CONFIGURATION");
                        return 42;
                    }
                }
                """);
        var suite = new TestSuite("suite-1", TestVisibility.PUBLIC, List.of("tests/First.java"),
                new JavaExecution("First"), List.of(new TestCaseDefinition("case-1")));
        var result = execute(singleSuite(original, hostSource("First", "case-1"), suite));
        assertThat(result.status()).isEqualTo(Verdict.ACCEPTED);
    }

    private static RunRequest singleSuite(RunRequest original, String host, TestSuite suite) {
        return new RunRequest(original.requestId(), original.taskVersionId(), original.language(),
                List.of(original.taskFiles().getFirst(),
                        new TaskFile("tests/First.java", FileType.VISIBLE_TEST, host)),
                original.submissionFiles(), List.of(suite), original.limits());
    }

    private static RunResult execute(RunRequest request) {
        var validator = new RunRequestValidator();
        var properties = new Judge0Properties(
                URI.create(System.getenv().getOrDefault("CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359")),
                1001, Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(60));
        try (var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var executor = new JavaRunExecutor(
                    new JavaSuitePlanner(validator, new SourceFileOverlay(validator)), new JavaSuiteArchive(),
                    new HttpJudge0Client(http, properties), new Judge0SuiteEvaluator(new SuiteReportParser()));
            return new RunSubmissionUseCase(validator, executor).execute(request).result();
        }
    }

    private static RunRequest request(String participantSource) {
        return new RunRequest("integration", "version", Language.JAVA, List.of(
                new TaskFile("src/Solution.java", FileType.EDITABLE,
                        "class Solution { static int answer() { return 0; } }"),
                new TaskFile("tests/First.java", FileType.VISIBLE_TEST, hostSource("First", "case-1")),
                new TaskFile("tests/Second.java", FileType.VISIBLE_TEST, hostSource("Second", "case-2"))),
                List.of(new SubmissionFile("src/Solution.java", participantSource)), List.of(
                new TestSuite("suite-1", TestVisibility.PUBLIC, List.of("tests/First.java"),
                        new JavaExecution("First"), List.of(new TestCaseDefinition("case-1"))),
                new TestSuite("suite-2", TestVisibility.PUBLIC, List.of("tests/Second.java"),
                        new JavaExecution("Second"), List.of(new TestCaseDefinition("case-2")))),
                new ExecutionLimits(2000, 5000, 128_000_000));
    }

    private static String hostSource(String className, String testId) {
        return """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class %s {
                    public static void main(String[] args) throws Exception {
                        int actual = Solution.answer();
                        String status = actual == 42 ? "ACCEPTED" : "WRONG_ANSWER";
                        Files.writeString(Path.of(System.getenv("CODELOBBY_REPORT_PATH")),
                            "{\\"testId\\":\\"%s\\",\\"status\\":\\"" + status
                            + "\\",\\"actualOutput\\":" + actual + "}\\n");
                        System.out.println("shared suite output");
                    }
                }
                """.formatted(className, testId);
    }
}
