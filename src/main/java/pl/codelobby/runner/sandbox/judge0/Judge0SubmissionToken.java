package pl.codelobby.runner.sandbox.judge0;

import java.util.regex.Pattern;

/** Validated engine identifier, separate from the internal API's request ID. */
public record Judge0SubmissionToken(String value) {

    private static final Pattern TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public Judge0SubmissionToken {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Judge0 submission token");
        }
    }
}
