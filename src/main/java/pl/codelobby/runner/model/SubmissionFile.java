package pl.codelobby.runner.model;

/** Participant contents for a declared EDITABLE path; no file role is accepted. */
public record SubmissionFile(String path, String content) {
}
