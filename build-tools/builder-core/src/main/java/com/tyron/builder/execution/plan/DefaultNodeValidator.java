package com.tyron.builder.execution.plan;


import static com.tyron.builder.internal.reflect.validation.TypeValidationProblemRenderer.convertToSingleLine;
import static com.tyron.builder.internal.reflect.validation.TypeValidationProblemRenderer.renderMinimalInformationAbout;

import com.tyron.builder.api.internal.GeneratedSubclasses;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationProblem;
import com.tyron.builder.internal.reflect.validation.UserManualReference;

import java.util.List;
import java.util.Optional;

public class DefaultNodeValidator implements NodeValidator {
    @Override
    public boolean hasValidationProblems(Node node) {
        if (node instanceof LocalTaskNode) {
            LocalTaskNode taskNode = (LocalTaskNode) node;
            WorkValidationContext validationContext = taskNode.getValidationContext();
            Class<?> taskType = GeneratedSubclasses.unpackType(taskNode.getTask());
            // We don't know whether the task is cacheable or not, so we ignore cacheability
            // problems for scheduling
            TypeValidationContext taskValidationContext =
                    validationContext.forType(taskType, false);
            taskNode.getTaskProperties().validateType(taskValidationContext);
            List<TypeValidationProblem> problems = validationContext.getProblems();
            problems.stream().filter(problem -> problem.getSeverity().isWarning())
                    .forEach(problem -> {
                        Optional<UserManualReference> userManualReference =
                                problem.getUserManualReference();
                        String docId = "more_about_tasks";
                        String section = "sec:up_to_date_checks";
                        if (userManualReference.isPresent()) {
                            UserManualReference docref = userManualReference.get();
                            docId = docref.getId();
                            section = docref.getSection();
                        }
                        // Because our deprecation warning system doesn't support multiline
                        // strings (bummer!) both in rendering
                        // **and** testing (no way to capture multiline deprecation warnings), we
                        // have to resort to removing details
                        // and rendering
                        String warning = convertToSingleLine(
                                renderMinimalInformationAbout(problem, false, false));
                        DeprecationLogger.deprecateBehaviour(warning).withContext(
                                "Execution optimizations are disabled to ensure correctness.")
                                .willBeRemovedInGradle8().withUserManual(docId, section).nagUser();
                    });
            return !problems.isEmpty();
        } else {
            return false;
        }
    }
}
