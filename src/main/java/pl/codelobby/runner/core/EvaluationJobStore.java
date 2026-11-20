package pl.codelobby.runner.core;

import java.util.Objects;
import java.util.Optional;

import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.SubmitRequest;

/** Job state storage only. Registration does not admit or schedule an execution. */
public interface EvaluationJobStore {

    Registration register(SubmitRequest request);

    Optional<EvaluationJob> find(String submissionId);

    EvaluationJob start(String submissionId);

    EvaluationJob finish(String submissionId, EvaluationResult result);

    EvaluationJob fail(String submissionId, JobError error);

    record Registration(EvaluationJob job, boolean created) {
        public Registration {
            Objects.requireNonNull(job, "job");
        }
    }
}
