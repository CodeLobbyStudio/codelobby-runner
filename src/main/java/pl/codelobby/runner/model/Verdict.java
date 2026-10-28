package pl.codelobby.runner.model;

/** Overall solution verdict, separate from asynchronous job state. */
public enum Verdict {
    ACCEPTED,
    WRONG_ANSWER,
    COMPILATION_ERROR,
    RUNTIME_ERROR,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    EXECUTION_ERROR
}
