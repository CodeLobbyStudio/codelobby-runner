package pl.codelobby.runner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmissionFile;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TaskFile;

/** File-manifest validation and immutable overlays only, without suite selection or execution. */
public final class SourceFileOverlay {

    private final RunRequestValidator validator;
    private final SubmitRequestValidator submitValidator = new SubmitRequestValidator();

    public SourceFileOverlay(RunRequestValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /** Full Run metadata/suite validation is still required before any later execution. */
    public List<PreparedSourceFile> apply(RunRequest request) {
        validator.validateFiles(request);
        return apply(request.taskFiles(), request.submissionFiles());
    }

    /** Validates the complete Submit before overlaying only permitted participant edits. */
    public List<PreparedSourceFile> applySubmit(SubmitRequest request) {
        submitValidator.validate(request);
        return apply(request.taskFiles(), request.submissionFiles());
    }

    private List<PreparedSourceFile> apply(List<TaskFile> taskFiles, List<SubmissionFile> submissionFiles) {
        Map<String, String> overrides = new HashMap<>();
        for (var file : submissionFiles) {
            overrides.put(file.path(), file.content());
        }

        var prepared = new ArrayList<PreparedSourceFile>(taskFiles.size());
        for (var file : taskFiles) {
            prepared.add(new PreparedSourceFile(file.path(), file.type(),
                    overrides.getOrDefault(file.path(), file.content())));
        }
        return List.copyOf(prepared);
    }
}
