package pl.codelobby.runner.languages.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.codelobby.runner.core.PreparedSourceFile;
import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.SubmitRequest;
import pl.codelobby.runner.model.TestCaseDefinition;
import pl.codelobby.runner.model.TestSuite;

/** Selects validated Java Run or Submit sources per suite without compiling or executing them. */
public final class JavaSuitePlanner {

    private final RunRequestValidator validator;
    private final SourceFileOverlay overlay;

    public JavaSuitePlanner(RunRequestValidator validator, SourceFileOverlay overlay) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
    }

    /** Plans retain request order. Resource budgets remain aggregate request-level concerns. */
    public List<JavaSuitePlan> prepare(RunRequest request) {
        validator.validate(request);
        requireJava(request.language());
        return select(overlay.apply(request), request.testSuites());
    }

    /** Submit includes public and hidden plans, with no test files shared across suites. */
    public List<JavaSuitePlan> prepareSubmit(SubmitRequest request) {
        var sources = overlay.applySubmit(request);
        requireJava(request.language());
        return select(sources, request.testSuites());
    }

    private void requireJava(Language language) {
        if (language != Language.JAVA) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST,
                    "Java planning requires the Java language");
        }
    }

    private List<JavaSuitePlan> select(List<PreparedSourceFile> sources, List<TestSuite> suites) {
        var plans = new ArrayList<JavaSuitePlan>(suites.size());
        for (var suite : suites) {
            Set<String> testPaths = Set.copyOf(suite.testFilePaths());
            var selected = sources.stream().filter(file -> file.type() == FileType.EDITABLE
                    || file.type() == FileType.READ_ONLY
                    || ((file.type() == FileType.VISIBLE_TEST || file.type() == FileType.HIDDEN_TEST)
                        && testPaths.contains(file.path()))).toList();
            // Full validation above establishes the language/profile match, not class existence in source.
            var execution = (JavaExecution) suite.execution();
            plans.add(new JavaSuitePlan(suite.suiteId(), execution.mainClass(), selected,
                    suite.cases().stream().map(TestCaseDefinition::testId).toList()));
        }
        return List.copyOf(plans);
    }
}
