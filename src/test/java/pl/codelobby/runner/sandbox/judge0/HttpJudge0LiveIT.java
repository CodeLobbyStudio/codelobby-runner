package pl.codelobby.runner.sandbox.judge0;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pl.codelobby.runner.model.ExecutionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in local sandbox checks. Run with -Pjudge0-integration verify after starting Judge0. */
@Tag("judge0-live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HttpJudge0LiveIT {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(2000, 5000, 128000000);
    private static final String PROGRAM = """
            public class Main {
                public static void main(String[] args) {
                    if (Runtime.version().feature() != 21) {
                        throw new IllegalStateException("Expected Java 21");
                    }
                    var values = java.util.List.of(2, 3);
                    System.out.println(values.getFirst() + values.getLast());
                }
            }
            """;

    @Autowired
    private Judge0Client client;

    @Autowired
    private Judge0Properties properties;

    @BeforeEach
    void requireLocalDevelopmentTarget() {
        assertThat(properties.baseUrl().getHost())
                .as("Live checks target local Judge0 only")
                .isIn("127.0.0.1", "localhost", "[::1]", "::1");
    }

    @Test
    void runsJava21ProgramThroughConfiguredClientAndReturnsOutputAndMeasurements() {
        var token = client.submitJava(new Judge0Submission(PROGRAM, "", LIMITS));

        var result = client.awaitResult(token);

        assertThat(result.token()).isEqualTo(token);
        assertThat(result.status()).isEqualTo(Judge0Status.ACCEPTED);
        assertThat(result.stdout()).isEqualTo("5\n");
        assertThat(result.stderr()).isNullOrEmpty();
        assertThat(result.compileOutput()).isNullOrEmpty();
        assertThat(result.exitCode()).isZero();
        assertThat(result.cpuTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.wallTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.memoryBytes()).isNotNull().isPositive();
    }

    @Test
    void returnsCompilationErrorAsEngineResultRatherThanCommunicationFailure() {
        var token = client.submitJava(new Judge0Submission(
                "public class Main { public static void main(String[] args) { int value = ; } }", "", LIMITS));

        var result = client.awaitResult(token);

        assertThat(result.token()).isEqualTo(token);
        assertThat(result.status()).isEqualTo(Judge0Status.COMPILATION_ERROR);
        assertThat(result.compileOutput()).isNotBlank();
        assertThat(result.stdout()).isNullOrEmpty();
    }

    @Test
    void reportsUnavailableLocalAddressWithoutStoppingTheRealSandbox() throws Exception {
        int unusedPort;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = reservation.getLocalPort();
        }
        var unavailable = new Judge0Properties(URI.create("http://127.0.0.1:" + unusedPort),
                properties.javaLanguageId(), Duration.ofMillis(250), Duration.ofSeconds(1),
                Duration.ofMillis(100), Duration.ofSeconds(3));
        try (var http = new Judge0Configuration().judge0HttpClient(unavailable)) {
            Judge0Client offline = new HttpJudge0Client(http, unavailable);

            assertThatThrownBy(() -> offline.submitJava(new Judge0Submission(PROGRAM, "", LIMITS)))
                    .isInstanceOfSatisfying(Judge0ClientException.class, exception -> {
                        assertThat(exception.reason()).isEqualTo(Judge0ClientException.Reason.UNAVAILABLE);
                        assertThat(exception.submissionToken()).isNull();
                        assertThat(exception.getCause()).isNull();
                    });
        }
    }
}
