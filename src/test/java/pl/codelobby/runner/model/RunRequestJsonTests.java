package pl.codelobby.runner.model;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunRequestJsonTests {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void readsTheCompleteJavaRunExample() throws Exception {
        var request = mapper.readValue(example("run-request.json").toFile(), RunRequest.class);

        assertThat(request.requestId()).isEqualTo("run-001");
        assertThat(request.taskVersionId()).isEqualTo("task-v2");
        assertThat(request.language()).isEqualTo(Language.JAVA);
        assertThat(request.taskFiles()).extracting(TaskFile::type).containsExactly(
                FileType.EDITABLE, FileType.READ_ONLY, FileType.VISIBLE_TEST);
        assertThat(request.submissionFiles()).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("src/Solution.java");
            assertThat(file.content()).contains("return Helpers.count(text);");
        });
        assertThat(request.testSuites()).singleElement().satisfies(suite -> {
            assertThat(suite.suiteId()).isEqualTo("public-suite");
            assertThat(suite.visibility()).isEqualTo(TestVisibility.PUBLIC);
            assertThat(suite.testFilePaths()).containsExactly("tests/PublicTests.java");
            assertThat(suite.execution()).isEqualTo(new JavaExecution("PublicTests"));
            assertThat(suite.cases()).containsExactly(new TestCaseDefinition("public-1"));
        });
        assertThat(request.limits()).isEqualTo(new ExecutionLimits(2000, 5000, 128000000L));
    }

    @ParameterizedTest
    @CsvSource({
            "run-request.json, JAVA, JAVA_MAIN, mainClass, PublicTests",
            "run-request-cpp.json, CPP, CPP_MAIN, mainSourcePath, tests/public_tests.cpp",
            "run-request-python.json, PYTHON, PYTHON_SCRIPT, scriptPath, tests/public_tests.py"
    })
    void preservesEachProfileWhenReadingAndWritingContractExamples(
            String file, Language language, String profile, String entryField, String entryValue)
            throws Exception {
        var original = mapper.readTree(example(file).toFile());
        var request = mapper.treeToValue(original, RunRequest.class);

        assertThat(request.language()).isEqualTo(language);
        var serialized = mapper.readTree(mapper.writeValueAsString(request));
        var execution = serialized.get("testSuites").get(0).get("execution");
        assertThat(execution.get("profile").asString()).isEqualTo(profile);
        assertThat(execution.get(entryField).asString()).isEqualTo(entryValue);
        assertThat(serialized).isEqualTo(original);
    }

    @Test
    void rejectsAnUnknownExecutionProfile() {
        var json = """
                {"profile":"SHELL","command":"echo unexpected"}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, ExecutionProfile.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void rejectsFieldsBelongingToAnotherExecutionProfile() {
        var json = """
                {"profile":"JAVA_MAIN","mainClass":"PublicTests","scriptPath":"test.py"}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, ExecutionProfile.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    private Path example(String file) {
        return Path.of("docs/api/examples", file);
    }
}
