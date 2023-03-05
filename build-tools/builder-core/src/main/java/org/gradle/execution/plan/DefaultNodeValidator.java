package org.gradle.execution.plan;

import org.gradle.api.internal.GeneratedSubclasses;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.internal.execution.WorkValidationContext;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.internal.reflect.validation.TypeValidationProblem;
import org.gradle.internal.reflect.validation.UserManualReference;

import java.util.List;

import static org.gradle.internal.reflect.validation.TypeValidationProblemRenderer.convertToSingleLine;
import static org.gradle.internal.reflect.validation.TypeValidationProblemRenderer.renderMinimalInformationAbout;

public class DefaultNodeValidator implements NodeValidator {
    @Override
    public boolean hasValidationProblems(LocalTaskNode node) {
        WorkValidationContext validationContext = node.getValidationContext();
        Class<?> taskType = GeneratedSubclasses.unpackType(node.getTask());
        // We don't know whether the task is cacheable or not, so we ignore cacheability problems for scheduling
        TypeValidationContext taskValidationContext = validationContext.forType(taskType, false);
        node.getTaskProperties().validateType(taskValidationContext);
        List<TypeValidationProblem> problems = validationContext.getProblems();
        problems.stream()
                .filter(problem -> problem.getSeverity().isWarning())
                .forEach(problem -> {
                    UserManualReference userManualReference = problem.getUserManualReference();
                    // Because our deprecation warning system doesn't support multiline strings (bummer!) both in rendering
                    // **and** testing (no way to capture multiline deprecation warnings), we have to resort to removing details
                    // and rendering
                    String warning = convertToSingleLine(renderMinimalInformationAbout(problem, false, false));
                    DeprecationLogger.deprecateBehaviour(warning)
                            .withContext("Execution optimizations are disabled to ensure correctness.")
                            .willBeRemovedInGradle8()
                            .withUserManual(userManualReference.getId(), userManualReference.getSection())
                            .nagUser();
                });
        return !problems.isEmpty();
    }
}
