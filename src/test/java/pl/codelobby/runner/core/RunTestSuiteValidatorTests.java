package pl.codelobby.runner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.CppExecution;
import pl.codelobby.runner.model.ExecutionProfile;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.PythonExecution;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunTestSuiteValidatorTests {

    private final RunRequestValidator validator = new RunRequestValidator();
    private RunRequest original;
    private TestSuite publicSuite;

    @BeforeEach
    void readJavaExample() throws Exception {
        original = example("run-request.json");
        publicSuite = original.testSuites().getFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-request.json", "run-request-cpp.json", "run-request-python.json"})
    void acceptsContractExamples(String file) throws Exception {
        var request = example(file);
        assertThatCode(() -> validator.validateTestSuites(request)).doesNotThrowAnyException();
    }

    @Test
    void acceptsMultipleSuitesWithDistinctFilesAndCaseSensitiveIds() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("tests/OtherTests.java", FileType.VISIBLE_TEST, "host test"));
        var second = new TestSuite("PUBLIC-SUITE", TestVisibility.PUBLIC, List.of("tests/OtherTests.java"),
                new JavaExecution("example.tests.OtherTests"), List.of(new TestCaseDefinition("PUBLIC-1")));
        assertThatCode(() -> validator.validateTestSuites(request(Language.JAVA, files, List.of(publicSuite, second))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSuitesAndLanguage() {
        invalid(request(Language.JAVA, original.taskFiles(), null));
        invalid(withSuites(List.of()));
        invalid(withSuites(Arrays.asList((TestSuite) null)));
        invalid(request(null, original.taskFiles(), List.of(publicSuite)));
    }

    @Test
    void rejectsHiddenSuitesAndHiddenFilesEvenIfUnreferenced() {
        invalid(withSuites(List.of(suite("suite", TestVisibility.HIDDEN, publicSuite.testFilePaths(),
                publicSuite.execution(), publicSuite.cases()))));
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("secret/HiddenTests.java", FileType.HIDDEN_TEST, "secret assertion"));
        invalid(request(Language.JAVA, files, List.of(publicSuite)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad id", "../id", "ą", "_starts-with-underscore"})
    void rejectsInvalidSuiteAndCaseIdentifiers(String id) {
        invalid(withSuites(List.of(suite(id, TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), List.of(new TestCaseDefinition(id))))));
    }

    @Test
    void enforcesIdentifierLengthAndAllowsTheMaximum() {
        var valid = suite("a".repeat(128), TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), List.of(new TestCaseDefinition("b".repeat(128))));
        assertThatCode(() -> validator.validateTestSuites(withSuites(List.of(valid)))).doesNotThrowAnyException();
        invalid(withSuites(List.of(suite("a".repeat(129), TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), List.of(new TestCaseDefinition("b".repeat(129)))))));
    }

    @Test
    void rejectsMissingSuiteFieldsAndNullCases() {
        invalid(withSuites(List.of(suite(null, TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", null, publicSuite.testFilePaths(),
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, null,
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, List.of(),
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                null, publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), null))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), List.of()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), Arrays.asList((TestCaseDefinition) null)))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), List.of(new TestCaseDefinition(null))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.java", "src/Solution.java", "src/Helpers.java", "tests/publictests.java", "../test.java"})
    void rejectsReferencesThatAreNotExactPublicTestPaths(String path) {
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, List.of(path),
                publicSuite.execution(), publicSuite.cases()))));
    }

    @Test
    void rejectsNullPathsDuplicateReferencesAndOrphanedFiles() {
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, Arrays.asList((String) null),
                publicSuite.execution(), publicSuite.cases()))));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC,
                List.of("tests/PublicTests.java", "tests/PublicTests.java"),
                publicSuite.execution(), publicSuite.cases()))));
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("tests/Unused.java", FileType.VISIBLE_TEST, "host test"));
        invalid(request(Language.JAVA, files, List.of(publicSuite)));
        invalid(withSuites(List.of(publicSuite, suite("other", TestVisibility.PUBLIC,
                publicSuite.testFilePaths(), publicSuite.execution(), List.of(new TestCaseDefinition("other"))))));
    }

    @Test
    void rejectsDuplicateSuiteIdsAndCaseIdsAcrossDifferentSuites() {
        var files = new ArrayList<>(original.taskFiles());
        files.add(new TaskFile("tests/Other.java", FileType.VISIBLE_TEST, "host test"));
        var duplicateSuiteId = suite(publicSuite.suiteId(), TestVisibility.PUBLIC, List.of("tests/Other.java"),
                new JavaExecution("Other"), List.of(new TestCaseDefinition("other")));
        invalid(request(Language.JAVA, files, List.of(publicSuite, duplicateSuiteId)));
        var duplicateCaseId = suite("other", TestVisibility.PUBLIC, List.of("tests/Other.java"),
                new JavaExecution("Other"), publicSuite.cases());
        invalid(request(Language.JAVA, files, List.of(publicSuite, duplicateCaseId)));
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                publicSuite.execution(), List.of(new TestCaseDefinition("same"), new TestCaseDefinition("same"))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "tests/PublicTests.java", ".Tests", "a..Tests", "java Main", "a.Main;exit"})
    void rejectsMalformedJavaEntryClasses(String mainClass) {
        invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                new JavaExecution(mainClass), publicSuite.cases()))));
    }

    @Test
    void rejectsMissingAndOverlongJavaEntryClasses() {
        for (String name : Arrays.asList(null, "A".repeat(241))) {
            invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                    new JavaExecution(name), publicSuite.cases()))));
        }
    }

    @Test
    void rejectsMismatchedLanguageProfiles() {
        for (var language : List.of(Language.CPP, Language.PYTHON)) {
            invalid(request(language, original.taskFiles(), List.of(publicSuite)));
        }
        for (ExecutionProfile profile : List.of(new CppExecution("tests/PublicTests.java"),
                new PythonExecution("tests/PublicTests.java"))) {
            invalid(withSuites(List.of(suite("suite", TestVisibility.PUBLIC, publicSuite.testFilePaths(),
                    profile, publicSuite.cases()))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-request-cpp.json", "run-request-python.json"})
    void requiresEntrySourceToBelongToItsOwnSuite(String file) throws Exception {
        var base = example(file);
        var suite = base.testSuites().getFirst();
        for (String entry : Arrays.asList(null, "src/solution.cpp", "src/solution.py", "tests/missing.cpp")) {
            ExecutionProfile profile = base.language() == Language.CPP
                    ? new CppExecution(entry) : new PythonExecution(entry);
            invalid(request(base.language(), base.taskFiles(), List.of(suite(suite.suiteId(), TestVisibility.PUBLIC,
                    suite.testFilePaths(), profile, suite.cases()))));
        }
    }

    private RunRequest example(String name) throws Exception {
        return JsonMapper.builder().build().readValue(Path.of("docs/api/examples", name).toFile(), RunRequest.class);
    }

    private TestSuite suite(String id, TestVisibility visibility, List<String> paths,
            ExecutionProfile profile, List<TestCaseDefinition> cases) {
        return new TestSuite(id, visibility, paths, profile, cases);
    }

    private RunRequest withSuites(List<TestSuite> suites) {
        return request(Language.JAVA, original.taskFiles(), suites);
    }

    private RunRequest request(Language language, List<TaskFile> files, List<TestSuite> suites) {
        return new RunRequest(original.requestId(), original.taskVersionId(), language,
                files, List.of(), suites, original.limits());
    }

    private void invalid(RunRequest request) {
        assertThatThrownBy(() -> validator.validateTestSuites(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid test suite configuration");
    }
}
