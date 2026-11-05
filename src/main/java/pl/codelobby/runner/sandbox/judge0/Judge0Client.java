package pl.codelobby.runner.sandbox.judge0;

/** Engine communication only. A returned token does not represent an evaluation verdict. */
public interface Judge0Client {

    Judge0SubmissionToken submitJava(Judge0Submission submission);

    /** One query, possibly returning a queued or processing state. */
    Judge0Result getSubmission(Judge0SubmissionToken token);

    /** Poll an existing token within result-timeout, without resubmitting the program. */
    Judge0Result awaitResult(Judge0SubmissionToken token);
}
