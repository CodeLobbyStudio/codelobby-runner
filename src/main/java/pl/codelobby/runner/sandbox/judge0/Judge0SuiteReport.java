package pl.codelobby.runner.sandbox.judge0;

import java.util.Objects;

/** Raw, untrusted suite report, separate from sandbox stdout and stderr. */
public record Judge0SuiteReport(State state, byte[] content) {

    static final int MAX_BYTES = 1_000_000;

    public enum State {
        AVAILABLE, MISSING, TOO_LARGE
    }

    public Judge0SuiteReport {
        Objects.requireNonNull(state, "state");
        if (state == State.AVAILABLE ? content == null || content.length == 0 || content.length > MAX_BYTES
                : content != null) {
            throw new IllegalArgumentException("Invalid suite report content");
        }
        content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }

    @Override
    public String toString() {
        return "Judge0SuiteReport[state=" + state + ", content=<redacted>]";
    }
}
