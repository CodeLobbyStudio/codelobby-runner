package pl.codelobby.runner.sandbox.judge0;

/** Judge0 engine states, not CodeLobby test verdicts. */
public enum Judge0Status {
    IN_QUEUE(1), PROCESSING(2), ACCEPTED(3), WRONG_ANSWER(4), TIME_LIMIT_EXCEEDED(5),
    COMPILATION_ERROR(6), RUNTIME_ERROR_SIGSEGV(7), RUNTIME_ERROR_SIGXFSZ(8),
    RUNTIME_ERROR_SIGFPE(9), RUNTIME_ERROR_SIGABRT(10), RUNTIME_ERROR_NZEC(11),
    RUNTIME_ERROR_OTHER(12), INTERNAL_ERROR(13), EXEC_FORMAT_ERROR(14);

    private final int id;

    Judge0Status(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean finished() {
        return this != IN_QUEUE && this != PROCESSING;
    }

    public static Judge0Status fromId(int id) {
        for (var status : values()) {
            if (status.id == id) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown Judge0 status");
    }
}
