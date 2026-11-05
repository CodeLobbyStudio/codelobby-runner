package pl.codelobby.runner.sandbox.judge0;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import pl.codelobby.runner.model.ExecutionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class Judge0SubmissionTests {

    private final ExecutionLimits limits = new ExecutionLimits(2000, 5000, 128000000);

    @Test
    void doesNotExposeSourceOrStdinInToString() {
        var submission = new Judge0Submission("private-source", "private-input", limits);
        assertThat(submission.toString()).contains("<redacted>").doesNotContain("private-source", "private-input");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void rejectsMissingSource(String source) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Judge0Submission(source, "", limits));
    }

    @Test
    void enforcesUtf8ByteLimitsAndRejectsMalformedUnicode() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Judge0Submission("ż".repeat(65537), "", limits));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Judge0Submission("source", "ż".repeat(32769), limits));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Judge0Submission("source\uD800", "", limits));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Judge0Submission("source", null, limits));
    }

    @Test
    void rejectsBudgetsOutsideContractBeforeAnyHttpRequest() {
        for (var invalid : new ExecutionLimits[] {
                new ExecutionLimits(0, 5000, 128000000), new ExecutionLimits(2001, 5000, 128000000),
                new ExecutionLimits(2000, 1999, 128000000), new ExecutionLimits(2000, 5001, 128000000),
                new ExecutionLimits(2000, 5000, 15999999), new ExecutionLimits(2000, 5000, 128000001)
        }) {
            assertThatIllegalArgumentException().isThrownBy(() -> new Judge0Submission("source", "", invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new Judge0Submission("source", "", null));
    }
}
