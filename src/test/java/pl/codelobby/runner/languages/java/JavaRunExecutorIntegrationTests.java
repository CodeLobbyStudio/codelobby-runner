package pl.codelobby.runner.languages.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
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
