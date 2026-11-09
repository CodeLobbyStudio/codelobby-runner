package pl.codelobby.runner.languages.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.codelobby.runner.core.RequestValidationException;
import pl.codelobby.runner.core.RunRequestValidator;
import pl.codelobby.runner.core.SourceFileOverlay;
import pl.codelobby.runner.core.ValidationErrorCode;
import pl.codelobby.runner.model.FileType;
import pl.codelobby.runner.model.JavaExecution;
import pl.codelobby.runner.model.Language;
import pl.codelobby.runner.model.RunRequest;
import pl.codelobby.runner.model.TestCaseDefinition;

/** Validates a public Java Run and selects sources for each suite without compiling or executing them. */
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
        if (request.language() != Language.JAVA) {
            throw new RequestValidationException(ValidationErrorCode.INVALID_REQUEST,
                    "Java planning requires the Java language");
        }
        var sources = overlay.apply(request);
        var plans = new ArrayList<JavaSuitePlan>(request.testSuites().size());
        for (var suite : request.testSuites()) {
            Set<String> testPaths = Set.copyOf(suite.testFilePaths());
            var selected = sources.stream().filter(file -> file.type() == FileType.EDITABLE
                    || file.type() == FileType.READ_ONLY
                    || (file.type() == FileType.VISIBLE_TEST && testPaths.contains(file.path()))).toList();
            // Full validation above establishes the language/profile match, not class existence in source.
            var execution = (JavaExecution) suite.execution();
            plans.add(new JavaSuitePlan(suite.suiteId(), execution.mainClass(), selected,
                    suite.cases().stream().map(TestCaseDefinition::testId).toList()));
        }
        return List.copyOf(plans);
    }
}
