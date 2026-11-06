package pl.codelobby.runner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import pl.codelobby.runner.model.RunRequest;

/** File-manifest validation and immutable overlays only, without suite selection or execution. */
public final class SourceFileOverlay {

    private final RunRequestValidator validator;

    public SourceFileOverlay(RunRequestValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /** Full Run metadata/suite validation is still required before any later execution. */
    public List<PreparedSourceFile> apply(RunRequest request) {
        validator.validateFiles(request);

        Map<String, String> overrides = new HashMap<>();
        for (var file : request.submissionFiles()) {
            overrides.put(file.path(), file.content());
        }

        var prepared = new ArrayList<PreparedSourceFile>(request.taskFiles().size());
        for (var file : request.taskFiles()) {
            prepared.add(new PreparedSourceFile(file.path(), file.type(),
                    overrides.getOrDefault(file.path(), file.content())));
        }
        return List.copyOf(prepared);
    }
}
