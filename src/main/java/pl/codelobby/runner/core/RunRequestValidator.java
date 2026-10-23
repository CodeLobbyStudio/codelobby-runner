package pl.codelobby.runner.core;

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

/** File and suite checks; remaining request metadata, resource limits and filesystem containment are separate stages. */
public final class RunRequestValidator {

    private static final int MAX_PATH_LENGTH = 240;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern DEVICE_NAME = Pattern.compile("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern JAVA_CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Includes file checks so suite references are never checked against an invalid manifest. */
    public void validateTestSuites(RunRequest request) {
        validateFiles(request);
        if (request.language() == null || request.testSuites() == null || request.testSuites().isEmpty()) {
            throw invalidTests();
        }
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
        for (var suite : request.testSuites()) {
            if (suite == null || !validIdentifier(suite.suiteId()) || !suiteIds.add(suite.suiteId())
                    || suite.visibility() != TestVisibility.PUBLIC
                    || suite.testFilePaths() == null || suite.testFilePaths().isEmpty()
                    || suite.cases() == null || suite.cases().isEmpty() || suite.execution() == null) {
                throw invalidTests();
            }
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

    private IllegalArgumentException invalidTests() {
        return new IllegalArgumentException("Invalid test suite configuration");
    }

    public void validateFiles(RunRequest request) {
        if (request == null || request.taskFiles() == null || request.taskFiles().isEmpty()
                || request.submissionFiles() == null) {
            throw invalidFiles();
        }

        Map<String, TaskFile> taskFiles = new HashMap<>();
        Map<String, String> canonicalPaths = new HashMap<>();
        Map<String, String> directorySpellings = new HashMap<>();
        for (var file : request.taskFiles()) {
            if (file == null || file.type() == null || file.content() == null) {
                throw invalidFiles();
            }
            validatePath(file.path());
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
        }
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

    private IllegalArgumentException invalidFiles() {
        // Keep diagnostics generic: requests may contain hidden file paths or contents.
        return new IllegalArgumentException("Invalid source file manifest");
    }
}
