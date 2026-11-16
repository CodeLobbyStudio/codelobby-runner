package pl.codelobby.runner.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_LOCAL_JUDGE0", matches = "true")
@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JUDGE0_REPORTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "runner.internal-key=synthetic-http-integration-key-123456789")
class RunEndpointIntegrationTests {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void localEngine(DynamicPropertyRegistry registry) {
        registry.add("runner.judge0.base-url",
                () -> System.getenv().getOrDefault("CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359"));
    }

    @ParameterizedTest
    @CsvSource({
            "ACCEPTED, ACCEPTED, 1",
            "WRONG_ANSWER, WRONG_ANSWER, 0",
            "COMPILATION_ERROR, NOT_RUN, 0",
            "EXECUTION_ERROR, NOT_RUN, 0",
            "TIME_LIMIT_EXCEEDED, NOT_RUN, 0"
    })
    void evaluatesJavaThroughTheAuthenticatedHttpEndpoint(
            Verdict verdict, TestVerdict caseVerdict, int passed) throws Exception {
        var mapper = JsonMapper.builder().build();
        var body = mapper.readTree(Path.of("docs/api/examples/run-request.json").toFile()).asObject();
        String source = switch (verdict) {
            case ACCEPTED, EXECUTION_ERROR -> body.get("submissionFiles").get(0).get("content").asString();
            case WRONG_ANSWER ->
                    "public class Solution { public static int countVowels(String text) { return 0; } }";
            case COMPILATION_ERROR -> "public class Solution { broken }";
            case TIME_LIMIT_EXCEEDED ->
                    "public class Solution { public static int countVowels(String text) { while (true) {} } }";
            default -> throw new AssertionError("Unexpected test verdict");
        };
        body.get("submissionFiles").get(0).asObject().put("content", source);
        if (verdict == Verdict.EXECUTION_ERROR) {
            for (var file : body.get("taskFiles")) {
                if (file.get("type").asString().equals("VISIBLE_TEST")) {
                    file.asObject().put("content", "public class PublicTests { broken host syntax }");
                }
            }
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/runs"))
                .timeout(Duration.ofSeconds(70))
                .header("Content-Type", "application/json")
                .header("X-CodeLobby-Internal-Key", "synthetic-http-integration-key-123456789")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();

        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var result = mapper.readValue(response.body(), RunResponse.class);
            assertThat(result.requestId()).isEqualTo(body.get("requestId").asString());
            assertThat(result.taskVersionId()).isEqualTo(body.get("taskVersionId").asString());
            assertThat(result.result().status()).isEqualTo(verdict);
            assertThat(result.result().passedTests()).isEqualTo(passed);
            assertThat(result.result().totalTests()).isEqualTo(1);
            assertThat(result.result().testResults()).extracting(test -> test.status()).containsExactly(caseVerdict);
            assertThat(result.result().compileOutput()).isNull();
            assertThat(response.body()).doesNotContain("synthetic-http-integration-key", "public class",
                    "Files.writeString", "tests/PublicTests.java");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsEveryPublicCaseAndItsFeedbackAcrossIndependentSuites(boolean oneWrongAnswer) throws Exception {
        var mapper = JsonMapper.builder().build();
        var original = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
        String solution = "public class Solution { public static int countVowels(String text) { "
                + (oneWrongAnswer ? "if (text.equals(\"ae\")) return 1; " : "")
                + "return Helpers.count(text); } }";
        var input = new RunRequest("multi-suite-http", original.taskVersionId(), original.language(), List.of(
                original.taskFiles().get(0), original.taskFiles().get(1),
                new TaskFile("tests/FirstPublicTests.java", FileType.VISIBLE_TEST,
                        publicSource("FirstPublicTests", "\"first\",\"second\"", "\"codelobby\",\"rhythm\"", "3,0")),
                new TaskFile("tests/SecondPublicTests.java", FileType.VISIBLE_TEST,
                        publicSource("SecondPublicTests", "\"third\"", "\"ae\"", "2"))),
                List.of(new SubmissionFile("src/Solution.java", solution)), List.of(
                new TestSuite("suite-one", TestVisibility.PUBLIC, List.of("tests/FirstPublicTests.java"),
                        new JavaExecution("FirstPublicTests"),
                        List.of(new TestCaseDefinition("first"), new TestCaseDefinition("second"))),
                new TestSuite("suite-two", TestVisibility.PUBLIC, List.of("tests/SecondPublicTests.java"),
                        new JavaExecution("SecondPublicTests"), List.of(new TestCaseDefinition("third")))),
                original.limits());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/runs"))
                .timeout(Duration.ofSeconds(70)).header("Content-Type", "application/json")
                .header("X-CodeLobby-Internal-Key", "synthetic-http-integration-key-123456789")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();

        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var envelope = mapper.readValue(response.body(), RunResponse.class);
            var result = envelope.result();
            assertThat(envelope.requestId()).isEqualTo(input.requestId());
            assertThat(envelope.taskVersionId()).isEqualTo(input.taskVersionId());
            assertThat(result.status()).isEqualTo(oneWrongAnswer ? Verdict.WRONG_ANSWER : Verdict.ACCEPTED);
            assertThat(result.totalTests()).isEqualTo(3);
            assertThat(result.passedTests()).isEqualTo(oneWrongAnswer ? 2 : 3);
            assertThat(result.testResults()).extracting(TestResult::testId).containsExactly("first", "second", "third");
            assertThat(result.testResults()).extracting(TestResult::suiteId)
                    .containsExactly("suite-one", "suite-one", "suite-two");
            assertThat(result.testResults()).extracting(TestResult::status).containsExactly(
                    TestVerdict.ACCEPTED, TestVerdict.ACCEPTED,
                    oneWrongAnswer ? TestVerdict.WRONG_ANSWER : TestVerdict.ACCEPTED);
            assertThat(result.testResults()).extracting(test -> test.input().get("text").asString())
                    .containsExactly("codelobby", "rhythm", "ae");
            assertThat(result.testResults()).extracting(test -> test.expectedOutput().asInt())
                    .containsExactly(3, 0, 2);
            assertThat(result.testResults()).extracting(test -> test.actualOutput().asInt())
                    .containsExactly(3, 0, oneWrongAnswer ? 1 : 2);
            assertThat(result.testResults()).allSatisfy(test -> {
                assertThat(test.visibility()).isEqualTo(TestVisibility.PUBLIC);
                assertThat(test.stdout()).isNull();
                assertThat(test.stderr()).isNull();
            });
            assertThat(result.compileOutput()).isNull();
            assertThat(response.body()).doesNotContain("host-source-sentinel", "FirstPublicTests.java",
                    "SecondPublicTests.java", "public class", "Files.writeString");
        }
    }

    private static String publicSource(String className, String ids, String inputs, String expected) {
        return """
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;
                public class %s {
                    public static void main(String[] args) throws Exception {
                        String[] ids = {%s};
                        String[] inputs = {%s};
                        int[] expected = {%s};
                        for (int i = 0; i < ids.length; i++) {
                            int actual = Solution.countVowels(inputs[i]);
                            String status = actual == expected[i] ? "ACCEPTED" : "WRONG_ANSWER";
                            String record = "{\\\"testId\\\":\\\"" + ids[i] + "\\\",\\\"status\\\":\\\"" + status
                                + "\\\",\\\"input\\\":{\\\"text\\\":\\\"" + inputs[i] + "\\\"},\\\"expectedOutput\\\":"
                                + expected[i] + ",\\\"actualOutput\\\":" + actual + "}\\n";
                            Files.writeString(Path.of(System.getenv("CODELOBBY_REPORT_PATH")), record,
                                    StandardOpenOption.APPEND);
                        }
                        System.out.println("host-source-sentinel");
                    }
                }
                """.formatted(className, ids, inputs, expected);
    }

}
