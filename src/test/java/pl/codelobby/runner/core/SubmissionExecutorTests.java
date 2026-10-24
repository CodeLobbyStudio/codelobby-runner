package pl.codelobby.runner.core;

import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.RunResponse;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestResult;
import pl.codelobby.runner.model.TestVerdict;
import pl.codelobby.runner.model.Verdict;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionExecutorTests {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({
            "run-accepted.json, ACCEPTED, ACCEPTED, 1",
            "run-wrong-answer.json, WRONG_ANSWER, WRONG_ANSWER, 0",
            "run-compilation-error.json, COMPILATION_ERROR, NOT_RUN, 0"
    })
    void exposesPredictableContractOutcomesThroughTheExecutorInterface(
            String example, Verdict verdict, TestVerdict caseVerdict, int passed) throws Exception {
        var request = mapper.readValue(Path.of("docs/api/examples/run-request.json").toFile(), RunRequest.class);
        new RunRequestValidator().validate(request);
        var expected = mapper.readValue(Path.of("docs/api/examples", example).toFile(), RunResponse.class).result();
        SubmissionExecutor executor = new StubSubmissionExecutor(expected);

        var result = executor.execute(request);

        assertThat(result.status()).isEqualTo(verdict);
        assertThat(result.passedTests()).isEqualTo(passed);
        assertThat(result.testResults()).extracting(TestResult::testId).containsExactlyElementsOf(
                request.testSuites().stream().flatMap(suite -> suite.cases().stream())
                        .map(TestCaseDefinition::testId).toList());
        assertThat(result.testResults()).extracting(TestResult::status).containsOnly(caseVerdict);
        assertThat(result).isEqualTo(expected);
        assertThat(executor.execute(request)).isEqualTo(result);
    }
}
