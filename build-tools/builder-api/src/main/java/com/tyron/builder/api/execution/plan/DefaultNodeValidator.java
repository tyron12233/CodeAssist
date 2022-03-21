package com.tyron.builder.api.execution.plan;


import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

import java.util.List;

public class DefaultNodeValidator implements NodeValidator {
    @Override
    public boolean hasValidationProblems(LocalTaskNode node) {
        WorkValidationContext validationContext = node.getValidationContext();
        Class<?> taskType = node.getTask().getClass();
        // We don't know whether the task is cacheable or not, so we ignore cacheability problems for scheduling
        TypeValidationContext taskValidationContext = validationContext.forType(taskType, false);
//        node.getTaskProperties().validateType(taskValidationContext);
        List<String> problems = validationContext.getProblems();
//        problems.stream()
//                .filter(problem -> problem.getSeverity().isWarning())
//                .forEach(problem -> {
//                    Optional<UserManualReference> userManualReference = problem.getUserManualReference();
//                    String docId = "more_about_tasks";
//                    String section = "sec:up_to_date_checks";
//                    if (userManualReference.isPresent()) {
//                        UserManualReference docref = userManualReference.get();
//                        docId = docref.getId();
//                        section = docref.getSection();
//                    }
//                    // Because our deprecation warning system doesn't support multiline strings (bummer!) both in rendering
//                    // **and** testing (no way to capture multiline deprecation warnings), we have to resort to removing details
//                    // and rendering
//                    String warning = convertToSingleLine(renderMinimalInformationAbout(problem, false, false));
//                    DeprecationLogger.deprecateBehaviour(warning)
//                            .withContext("Execution optimizations are disabled to ensure correctness.")
//                            .willBeRemovedInGradle8()
//                            .withUserManual(docId, section)
//                            .nagUser();
//                });
        return !problems.isEmpty();
    }
}