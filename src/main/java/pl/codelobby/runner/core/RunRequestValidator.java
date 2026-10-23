package pl.codelobby.runner.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.TaskFile;
import pl.codelobby.runner.model.CppExecution;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.PythonExecution;
import pl.codelobby.runner.model.TestSuite;
import pl.codelobby.runner.model.TestVisibility;

/** Semantic request checks; HTTP body limits and filesystem containment are separate stages. */
public final class RunRequestValidator {

    private static final int MAX_PATH_LENGTH = 240;
    private static final int MAX_FILES = 50;
    private static final int MAX_SUITES = 50;
    private static final int MAX_CASES = 50;
    private static final int MAX_FILE_BYTES = 131072;
    private static final int MAX_PROJECT_BYTES = 1048576;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern DEVICE_NAME = Pattern.compile("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern JAVA_CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Validates metadata, budgets, file manifests and suite relationships without executing code. */
    public void validate(RunRequest request) {
        if (request == null || !validIdentifier(request.requestId()) || !validIdentifier(request.taskVersionId())
                || request.limits() == null) {
            throw invalidRequest();
        }
        var limits = request.limits();
        if (limits.cpuTimeMs() < 1 || limits.cpuTimeMs() > 2000
                || limits.wallTimeMs() < 1 || limits.wallTimeMs() > 5000
                || limits.wallTimeMs() < limits.cpuTimeMs()
                || limits.memoryLimitBytes() < 16000000L || limits.memoryLimitBytes() > 128000000L) {
            throw invalidRequest();
        }
        validateTestSuites(request);
    }

    /** Includes file checks so suite references are never checked against an invalid manifest. */
    public void validateTestSuites(RunRequest request) {
        validateFiles(request);
        if (request.language() == null || request.testSuites() == null || request.testSuites().isEmpty()) {
            throw invalidTests();
        }
        checkCount(request.testSuites().size(), MAX_SUITES);
        Map<String, TaskFile> files = new HashMap<>();
        for (var file : request.taskFiles()) {
            if (file.type() == FileType.HIDDEN_TEST) {
                throw invalidTests();
            }
            files.put(file.path(), file);
        }
        Set<String> suiteIds = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        Set<String> assignedFiles = new HashSet<>();
        long totalCases = 0;
        for (var suite : request.testSuites()) {
            if (suite == null || !validIdentifier(suite.suiteId()) || !suiteIds.add(suite.suiteId())
                    || suite.visibility() != TestVisibility.PUBLIC
                    || suite.testFilePaths() == null || suite.testFilePaths().isEmpty()
                    || suite.cases() == null || suite.cases().isEmpty() || suite.execution() == null) {
                throw invalidTests();
            }
            checkCount(suite.testFilePaths().size(), MAX_FILES);
            totalCases += suite.cases().size();
            checkCount(totalCases, MAX_CASES);
            for (var path : suite.testFilePaths()) {
                var file = files.get(path);
                if (file == null || file.type() != FileType.VISIBLE_TEST || !assignedFiles.add(path)) {
                    throw invalidTests();
                }
            }
            for (var testCase : suite.cases()) {
                if (testCase == null || !validIdentifier(testCase.testId()) || !caseIds.add(testCase.testId())) {
                    throw invalidTests();
                }
            }
            boolean validProfile = switch (request.language()) {
                case JAVA -> suite.execution() instanceof JavaExecution execution
                        && execution.mainClass() != null && execution.mainClass().length() <= MAX_PATH_LENGTH
                        && JAVA_CLASS.matcher(execution.mainClass()).matches();
                case CPP -> suite.execution() instanceof CppExecution execution
                        && validEntryPath(suite, execution.mainSourcePath(), ".cpp");
                case PYTHON -> suite.execution() instanceof PythonExecution execution
                        && validEntryPath(suite, execution.scriptPath(), ".py");
            };
            if (!validProfile) {
                throw invalidTests();
            }
        }
        if (files.values().stream().anyMatch(file -> file.type() == FileType.VISIBLE_TEST
                && !assignedFiles.contains(file.path()))) {
            throw invalidTests();
        }
    }

    private boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private boolean validEntryPath(TestSuite suite, String path, String extension) {
        return path != null && path.endsWith(extension) && suite.testFilePaths().contains(path);
    }

    private RequestValidationException invalidTests() {
        return new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Invalid test suite configuration");
    }

    public void validateFiles(RunRequest request) {
        if (request == null || request.taskFiles() == null || request.taskFiles().isEmpty()
                || request.submissionFiles() == null) {
            throw invalidFiles();
        }
        checkCount(request.taskFiles().size(), MAX_FILES);
        checkCount(request.submissionFiles().size(), MAX_FILES);

        Map<String, TaskFile> taskFiles = new HashMap<>();
        Map<String, Integer> effectiveSizes = new HashMap<>();
        Map<String, String> canonicalPaths = new HashMap<>();
        Map<String, String> directorySpellings = new HashMap<>();
        for (var file : request.taskFiles()) {
            if (file == null || file.type() == null || file.content() == null) {
                throw invalidFiles();
            }
            validatePath(file.path());
            effectiveSizes.put(file.path(), fileBytes(file.content()));
            String canonical = file.path().toLowerCase(Locale.ROOT);
            if (canonicalPaths.putIfAbsent(canonical, file.path()) != null) {
                throw invalidFiles();
            }
            taskFiles.put(file.path(), file);
            for (int slash = file.path().indexOf('/'); slash >= 0;
                    slash = file.path().indexOf('/', slash + 1)) {
                String directory = file.path().substring(0, slash);
                String previous = directorySpellings.putIfAbsent(directory.toLowerCase(Locale.ROOT), directory);
                if (previous != null && !previous.equals(directory)) {
                    throw invalidFiles();
                }
            }
        }
        if (taskFiles.values().stream().noneMatch(file -> file.type() == FileType.EDITABLE)
                || directorySpellings.keySet().stream().anyMatch(canonicalPaths::containsKey)) {
            throw invalidFiles();
        }

        Set<String> overrides = new HashSet<>();
        for (var file : request.submissionFiles()) {
            if (file == null || file.content() == null) {
                throw invalidFiles();
            }
            validatePath(file.path());
            var original = taskFiles.get(file.path());
            if (!overrides.add(file.path().toLowerCase(Locale.ROOT))
                    || original == null || original.type() != FileType.EDITABLE) {
                throw invalidFiles();
            }
            effectiveSizes.put(file.path(), fileBytes(file.content()));
        }
        long projectBytes = effectiveSizes.values().stream().mapToLong(Integer::longValue).sum();
        checkCount(projectBytes, MAX_PROJECT_BYTES);
    }

    private int fileBytes(String content) {
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        checkCount(bytes, MAX_FILE_BYTES);
        return bytes;
    }

    private void checkCount(long actual, long maximum) {
        if (actual > maximum) {
            throw new RequestValidationException(ValidationErrorCode.PAYLOAD_TOO_LARGE,
                    "Request size or count limit exceeded");
        }
    }

    private RequestValidationException invalidRequest() {
        return new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Invalid request metadata or limits");
    }

    private void validatePath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            throw invalidFiles();
        }
        for (String segment : path.split("/", -1)) {
            String upper = segment.toUpperCase(Locale.ROOT);
            String basename = upper.split("\\.", -1)[0];
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.equalsIgnoreCase(".codelobby")
                    || DEVICE_NAME.matcher(basename).matches()) {
                throw invalidFiles();
            }
        }
    }

    private RequestValidationException invalidFiles() {
        // Keep diagnostics generic: requests may contain hidden file paths or contents.
        return new RequestValidationException(ValidationErrorCode.INVALID_SOURCE_FILES, "Invalid source file manifest");
    }
}
