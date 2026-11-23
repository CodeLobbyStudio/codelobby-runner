package pl.codelobby.runner.languages.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0Properties;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_LOCAL_JUDGE0", matches = "true")
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JUDGE0_REPORTS", matches = "true")
class JavaSubmitEvaluationRunnerIntegrationTests {

    @ParameterizedTest
    @CsvSource({"accepted,ACCEPTED,2", "wrong,WRONG_ANSWER,0", "compilation,COMPILATION_ERROR,0"})
    void evaluatesTheWholePublicAndHiddenManifestWithSharedBudgets(String scenario, Verdict expected, int passed) {
        var original = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
        String source = switch (scenario) {
            case "accepted" -> original.submissionFiles().getFirst().content();
            case "wrong" -> "public class Solution { public static int countVowels(String text) { return 0; } }";
            default -> "public class Solution { broken syntax }";
        };
        var request = new SubmitRequest("aggregate-" + scenario, original.taskVersionId(), original.language(),
                original.taskFiles(), List.of(new SubmissionFile("src/Solution.java", source)),
                original.testSuites(), original.limits());
        var properties = new Judge0Properties(URI.create(System.getenv().getOrDefault(
                "CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359")), 1001,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(60));
        try (var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var validator = new RunRequestValidator();
            var single = new JavaSubmitSuiteExecutor(new JavaSuitePlanner(validator, new SourceFileOverlay(validator)),
                    new JavaSuiteArchive(), new HttpJudge0Client(http, properties),
                    new Judge0SuiteEvaluator(new SuiteReportParser()));
            var result = new JavaSubmitEvaluationRunner(single).evaluate(request, Duration.ofSeconds(120));

            assertThat(result.status()).isEqualTo(expected);
            assertThat(result.passedTests()).isEqualTo(passed);
            assertThat(result.totalTests()).isEqualTo(2);
            assertThat(result.testResults()).extracting(TestResult::testId).containsExactly("public-1", "hidden-1");
            assertThat(result.compilation().cpuTimeMs()).isPositive();
            if (expected != Verdict.COMPILATION_ERROR) {
                assertThat(result.sandboxCpuTimeMs()).isPositive();
                assertThat(result.sandboxWallTimeMs()).isPositive();
                assertThat(result.sandboxPeakMemoryBytes()).isPositive();
            }
            var hidden = result.testResults().getLast();
            assertThat(hidden.visibility()).isEqualTo(TestVisibility.HIDDEN);
            assertThat(hidden.input()).isNull();
            assertThat(hidden.expectedOutput()).isNull();
            assertThat(hidden.actualOutput()).isNull();
            assertThat(hidden.stdout()).isNull();
            assertThat(hidden.stderr()).isNull();
        }
    }
}
