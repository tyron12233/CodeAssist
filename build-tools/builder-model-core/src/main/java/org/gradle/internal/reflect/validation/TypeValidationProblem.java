package org.gradle.internal.reflect.validation;

import org.gradle.internal.reflect.problems.ValidationProblemId;
import org.gradle.problems.BaseProblem;
import org.gradle.problems.Solution;

import java.util.List;
import java.util.function.Supplier;

public class TypeValidationProblem extends BaseProblem<ValidationProblemId, Severity, TypeValidationProblemLocation> {
    private final UserManualReference userManualReference;
    private final boolean onlyAffectsCacheableWork;

    public TypeValidationProblem(ValidationProblemId id,
                                 Severity severity,
                                 TypeValidationProblemLocation where,
                                 Supplier<String> shortDescription,
                                 Supplier<String> longDescription,
                                 Supplier<String> reason,
                                 boolean onlyAffectsCacheableWork,
                                 UserManualReference userManualReference,
                                 List<Supplier<Solution>> solutions) {
        super(id,
                severity,
                where,
                shortDescription,
                longDescription,
                reason,
                () -> userManualReference == null ? null : userManualReference.toDocumentationLink(),
                solutions);
        this.userManualReference = userManualReference;
        this.onlyAffectsCacheableWork = onlyAffectsCacheableWork;
    }

    public UserManualReference getUserManualReference() {
        return userManualReference;
    }

    public boolean isOnlyAffectsCacheableWork() {
        return onlyAffectsCacheableWork;
    }
}
