package pl.codelobby.runner.core;

import java.util.Objects;

/** Raw internal harness report plus process exit code; never return this directly through the API. */
public record ExecutionReport(String report, int exitCode) {

    public ExecutionReport {
        Objects.requireNonNull(report, "report");
    }
}
