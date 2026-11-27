package pl.codelobby.runner.languages.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.codelobby.runner.core.PreparedSourceFile;
import pl.codelobby.runner.core.SuiteReportParser;
import pl.codelobby.runner.model.ExecutionLimits;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;
import pl.codelobby.runner.model.Verdict;
import pl.codelobby.runner.sandbox.judge0.HttpJudge0Client;
import pl.codelobby.runner.sandbox.judge0.Judge0Properties;
import pl.codelobby.runner.sandbox.judge0.Judge0Status;
import pl.codelobby.runner.sandbox.judge0.Judge0SuiteEvaluator;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CODELOBBY_TEST_JAVA_ARTIFACTS", matches = "true")
class JavaCompiledSuiteArchiveIntegrationTests {

    private static final String PREFIX = "codelobby:java-artifacts:prototype:v1:";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @EnumSource(TestVisibility.class)
    void replaysTheSameCapturedClassesInFreshSandboxesWithoutCompilation(TestVisibility visibility) throws Exception {
        String source = """
                package checks;
                import example.Solution;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.security.MessageDigest;
                import java.util.HexFormat;
                public class Tests {
                    public static void main(String[] args) throws Exception {
                        Path state = Path.of(".codelobby/round-state");
                        if (Files.exists(state) || Solution.value() != 42) throw new AssertionError();
                        Files.writeString(state, "runtime state must not survive");
                        Files.writeString(Path.of(System.getenv("CODELOBBY_REPORT_PATH")),
                            "{\\"testId\\":\\"case-1\\",\\"status\\":\\"ACCEPTED\\",\\"actualOutput\\":42}\\n");
                        Path bytes = Path.of(".codelobby/classes/example/Solution.class");
                        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bytes))));
                        Files.write(bytes, new byte[]{1, 2, 3, 4});
                    }
                }
                """;
        var plan = new JavaSuitePlan("suite-1", "checks.Tests", List.of(
                new PreparedSourceFile("src/example/Solution.java", FileType.EDITABLE,
                        "package example; public class Solution { public static int value() { return 42; } }"),
                new PreparedSourceFile("tests/checks/Tests.java",
                        visibility == TestVisibility.PUBLIC ? FileType.VISIBLE_TEST : FileType.HIDDEN_TEST, source)),
                List.of("case-1"));
        var suite = new TestSuite("suite-1", visibility, List.of("tests/checks/Tests.java"),
                new JavaExecution("checks.Tests"), List.of(new TestCaseDefinition("case-1")));
        var properties = new Judge0Properties(URI.create("http://127.0.0.1:2359"), 1001,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofSeconds(60));
        var limits = new ExecutionLimits(2000, 5000, 128000000);
        String captureToken = null;
        try (var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var client = new HttpJudge0Client(http, properties);
            var original = client.submitProject(new JavaSuiteArchive().createSubmit(plan, visibility, 10000, 20000), limits);
            captureToken = original.value();
            var first = client.awaitResult(original);
            assertThat(first.status()).isEqualTo(Judge0Status.ACCEPTED);
            var snapshot = mapper.readTree(redis("GET", PREFIX + captureToken));
            assertThat(snapshot.get("state").asString()).isEqualTo("AVAILABLE");
            var artifacts = JavaCompiledArtifacts.read(Base64.getDecoder().decode(snapshot.get("snapshot").asString()),
                    snapshot.get("sha256").asString());
            byte[] zip = new JavaCompiledSuiteArchive().create(artifacts, "checks.Tests");
            var evaluator = new Judge0SuiteEvaluator(new SuiteReportParser());

            for (int round = 0; round < 2; round++) {
                var token = client.submitProject(zip, limits);
                var engine = client.awaitResult(token);
                var report = client.getSuiteReport(token);
                assertThat(engine.status()).isEqualTo(Judge0Status.ACCEPTED);
                assertThat(engine.stdout()).isEqualTo(first.stdout());
                assertThat(engine.compileOutput()).isNull();
                assertThat(report.compilation()).isNotNull();
                assertThat(report.compilation().cpuTimeMs()).isZero();
                assertThat(report.compilation().wallTimeMs()).isZero();
                assertThat(evaluator.evaluate(suite, engine, report).status()).isEqualTo(Verdict.ACCEPTED);
                var test = evaluator.evaluate(suite, engine, report).testResults().getFirst();
                if (visibility == TestVisibility.HIDDEN) {
                    assertThat(test.actualOutput()).isNull();
                } else {
                    assertThat(test.actualOutput().intValue()).isEqualTo(42);
                }
                assertThat(redis("GET", PREFIX + token.value())).isBlank();
            }
        } finally {
            if (captureToken != null) {
                redis("DEL", PREFIX + captureToken);
                redis("ZREM", PREFIX + "index", PREFIX + captureToken);
            }
        }
    }

    /** Test-only private-store access. Production retrieval is a separate unfinished stage. */
    private String redis(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "--env-file",
                System.getenv().getOrDefault("JUDGE0_CONFIG_FILE", "judge0.conf"),
                "-f", "compose.yaml", "-f", "compose.reports.yaml", "-f", "compose.artifacts.experimental.yaml",
                "exec", "-T", "redis", "sh", "-c",
                "export REDISCLI_AUTH=\"$REDIS_PASSWORD\"\nexec redis-cli --raw \"$@\"", "sh"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(Path.of("dev/judge0").toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            var output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readNBytes(2000513);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            assertThat(bytes.length).isLessThanOrEqualTo(2000512);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } finally {
            process.destroyForcibly();
        }
    }
}
