package pl.codelobby.runner.sandbox.judge0;

/** Engine communication only. A returned token does not represent an evaluation verdict. */
public interface Judge0Client {

    Judge0SubmissionToken submitJava(Judge0Submission submission);
}
