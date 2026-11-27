package pl.codelobby.runner.model;

/** Processing state, independent of the evaluated solution verdict. */
public enum JobState {
    QUEUED,
    RUNNING,
    FINISHED,
    FAILED
}
