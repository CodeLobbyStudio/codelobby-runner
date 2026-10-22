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

/** File-manifest checks only; metadata, resource limits and filesystem containment are separate stages. */
public final class RunRequestValidator {

    private static final int MAX_PATH_LENGTH = 240;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern DEVICE_NAME = Pattern.compile("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");

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
