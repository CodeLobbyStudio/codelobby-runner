package pl.codelobby.runner.core;

import java.util.Objects;
import java.util.Optional;

import pl.codelobby.runner.model.EvaluationJob;
import pl.codelobby.runner.model.EvaluationResult;
import pl.codelobby.runner.model.JobError;
import pl.codelobby.runner.model.SubmitRequest;

/** Job state storage with an optional atomic admission hook. Does not schedule or execute work. */
public interface EvaluationJobStore {

    default Registration register(SubmitRequest request) {
        return register(request, () -> { });
    }

    /** Reserve capacity synchronously before publication. The callback must not schedule or re-enter the store. */
    Registration register(SubmitRequest request, Runnable beforePublish);

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
