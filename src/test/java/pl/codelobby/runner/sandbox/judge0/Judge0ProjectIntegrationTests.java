package pl.codelobby.runner.sandbox.judge0;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.core.PreparedSourceFile;
import pl.codelobby.runner.languages.java.JavaSuiteArchive;
import pl.codelobby.runner.languages.java.JavaSuitePlan;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_LOCAL_JUDGE0", matches = "true")
class Judge0ProjectIntegrationTests {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void compilesAndRunsAProjectOrReportsACompilationError(boolean validSource) {
        var plan = new JavaSuitePlan("public-suite", "checks.PublicTests", List.of(
                new PreparedSourceFile("src/example/Solution.java", FileType.EDITABLE, validSource
                        ? "package example; public class Solution { public static int value() { return 42; } }"
                        : "package example; public class Solution { public static int value() { return; } }"),
                new PreparedSourceFile("tests/checks/PublicTests.java", FileType.VISIBLE_TEST, """
                        package checks;
                        import example.Solution;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        public class PublicTests {
                            public static void main(String[] args) throws Exception {
                                var report = Path.of(System.getenv("CODELOBBY_REPORT_PATH"));
                                if (Files.size(report) != 0 || Solution.value() != 42) {
                                    throw new AssertionError();
                                }
                                Files.writeString(report, "local integration probe");
                                System.out.println("project-ok");
                            }
                        }
                        """)), List.of("public-1"));
        byte[] zip = new JavaSuiteArchive().create(plan);
        var properties = new Judge0Properties(URI.create(System.getenv().getOrDefault("CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359")), 1001,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(60));
        try (var http = new Judge0Configuration().judge0HttpClient(properties)) {
            Judge0Client client = new HttpJudge0Client(http, properties);
            var token = client.submitProject(zip, new ExecutionLimits(2000, 5000, 128000000));
            var result = client.awaitResult(token);

            if (validSource) {
                assertThat(result.status()).isEqualTo(Judge0Status.ACCEPTED);
                assertThat(result.stdout()).isEqualTo("project-ok\n");
            } else {
                assertThat(result.status()).isEqualTo(Judge0Status.COMPILATION_ERROR);
                assertThat(result.compileOutput()).contains("missing return value");
            }
        }
    }

    @EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JUDGE0_REPORTS", matches = "true")
    @ParameterizedTest
    @EnumSource(Judge0SuiteReport.State.class)
    void retrievesTheReportSeparatelyFromProgramOutput(Judge0SuiteReport.State expected) {
        String source = """
                package checks;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class PublicTests {
                    public static void main(String[] args) throws Exception {
                        var report = Path.of(System.getenv("CODELOBBY_REPORT_PATH"));
                        %s
                        System.out.println("ordinary output");
                        System.err.println("ordinary error");
                    }
                }
                """.formatted(switch (expected) {
                    case AVAILABLE -> """
                            Files.writeString(report, "{\\\"testId\\\":\\\"public-1\\\",\\\"status\\\":\\\"ACCEPTED\\\"}\\n");
                            """;
                    case MISSING -> "Files.delete(report);";
                    case TOO_LARGE -> "Files.write(report, new byte[1_000_001]);";
                });
        var plan = new JavaSuitePlan("public-suite", "checks.PublicTests", List.of(
                new PreparedSourceFile("src/Solution.java", FileType.EDITABLE, "class Solution {}"),
                new PreparedSourceFile("tests/checks/PublicTests.java", FileType.VISIBLE_TEST, source)),
                List.of("public-1"));
        var properties = new Judge0Properties(
                URI.create(System.getenv().getOrDefault("CODELOBBY_TEST_JUDGE0_URL", "http://127.0.0.1:2359")),
                1001, Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(60));
        try (var http = new Judge0Configuration().judge0HttpClient(properties)) {
            Judge0Client client = new HttpJudge0Client(http, properties);
            var token = client.submitProject(new JavaSuiteArchive().create(plan),
                    new ExecutionLimits(2000, 5000, 128000000));

            var result = client.awaitResult(token);
            assertThat(result.status()).isEqualTo(Judge0Status.ACCEPTED);
            assertThat(result.stdout()).isEqualTo("ordinary output\n");
            assertThat(result.stderr()).isEqualTo("ordinary error\n");
            var report = client.getSuiteReport(token);

            assertThat(report.state()).isEqualTo(expected);
            if (expected == Judge0SuiteReport.State.AVAILABLE) {
                assertThat(new String(report.content(), StandardCharsets.UTF_8))
                        .isEqualTo("{\"testId\":\"public-1\",\"status\":\"ACCEPTED\"}\n");
            } else {
                assertThat(report.content()).isNull();
            }
        }
    }
}
