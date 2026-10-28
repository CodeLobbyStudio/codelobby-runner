package pl.codelobby.runner.model;

/** Per-case status; compilation failure is reported overall, with cases NOT_RUN. */
public enum TestVerdict {
    ACCEPTED,
    WRONG_ANSWER,
    RUNTIME_ERROR,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    EXECUTION_ERROR,
    NOT_RUN
}
