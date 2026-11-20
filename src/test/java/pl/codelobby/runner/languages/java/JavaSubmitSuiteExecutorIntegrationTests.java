package pl.codelobby.runner.languages.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0Properties;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_LOCAL_JUDGE0", matches = "true")
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JUDGE0_REPORTS", matches = "true")
class JavaSubmitSuiteExecutorIntegrationTests {

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "wrong", "compilation"})
    void executesRealPublicAndHiddenSuitesWithoutReturningHiddenDiagnostics(String scenario) {
        var original = JsonMapper.builder().build().readValue(
                Path.of("docs/api/examples/submit-request.json").toFile(), SubmitRequest.class);
        String content = switch (scenario) {
            case "accepted" -> original.submissionFiles().getFirst().content();
            case "wrong" -> "public class Solution { public static int countVowels(String text) { return 0; } }";
            default -> "public class Solution { broken syntax }";
        };
        var request = new SubmitRequest("integration-" + scenario, original.taskVersionId(), original.language(),
                original.taskFiles(), List.of(new SubmissionFile("src/Solution.java", content)),
                original.testSuites(), original.limits());
        var properties = new Judge0Properties(URI.create(System.getenv().getOrDefault(
                "CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359")), 1001,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(60));
        try (var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var validator = new RunRequestValidator();
            var executor = new JavaSubmitSuiteExecutor(new JavaSuitePlanner(validator, new SourceFileOverlay(validator)),
                    new JavaSuiteArchive(), new HttpJudge0Client(http, properties),
                    new Judge0SuiteEvaluator(new SuiteReportParser()));
            for (int index = 0; index < request.testSuites().size(); index++) {
                var result = executor.execute(request, index, request.limits(), 10000, 20000, Duration.ofSeconds(60));
                assertThat(result.evaluation().status()).isEqualTo(switch (scenario) {
                    case "accepted" -> Verdict.ACCEPTED;
                    case "wrong" -> Verdict.WRONG_ANSWER;
                    default -> Verdict.COMPILATION_ERROR;
                });
                var test = result.evaluation().testResults().getFirst();
                assertThat(test.status()).isEqualTo(switch (scenario) {
                    case "accepted" -> TestVerdict.ACCEPTED;
                    case "wrong" -> TestVerdict.WRONG_ANSWER;
                    default -> TestVerdict.NOT_RUN;
                });
                if (test.visibility() == TestVisibility.HIDDEN) {
                    assertThat(test.input()).isNull();
                    assertThat(test.expectedOutput()).isNull();
                    assertThat(test.actualOutput()).isNull();
                    assertThat(test.stdout()).isNull();
                    assertThat(test.stderr()).isNull();
                } else if (!scenario.equals("compilation")) {
                    assertThat(test.input()).isNotNull();
                    assertThat(test.expectedOutput()).isNotNull();
                    assertThat(test.actualOutput()).isNotNull();
                }
                assertThat(result.compilation()).isNotNull();
            }
        }
    }
}
