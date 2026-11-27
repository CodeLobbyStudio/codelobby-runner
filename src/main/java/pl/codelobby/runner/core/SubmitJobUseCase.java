package pl.codelobby.runner.core;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.SubmitRequest;

/** Validates before admission and exposes snapshots without starting work during reads. */
public final class SubmitJobUseCase {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final SubmitRequestValidator validator = new SubmitRequestValidator();
    private final EvaluationJobStore jobs;
    private final Supplier<AsyncSubmitCoordinator> coordinator;

    public SubmitJobUseCase(EvaluationJobStore jobs, Supplier<AsyncSubmitCoordinator> coordinator) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public EvaluationJobStore.Registration submit(SubmitRequest request) {
        validator.validate(request);
        if (request.language() != Language.JAVA) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Unsupported Submit language");
        }
        return available().submit(request);
    }

    public EvaluationJob find(String id) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST, "Invalid submission identifier");
        }
        available();
        return jobs.find(id).orElseThrow(EvaluationJobNotFoundException::new);
    }

    private AsyncSubmitCoordinator available() {
        var execution = coordinator.get();
        if (execution == null) {
            throw new ExecutionUnavailableException();
        }
        return execution;
    }
}
