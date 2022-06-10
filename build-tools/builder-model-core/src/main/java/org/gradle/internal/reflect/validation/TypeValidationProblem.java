package org.gradle.internal.reflect.validation;

import org.gradle.internal.reflect.problems.ValidationProblemId;
import org.gradle.problems.BaseProblem;
import org.gradle.problems.Solution;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class TypeValidationProblem extends BaseProblem<ValidationProblemId, Severity, TypeValidationProblemLocation> {
    @Nullable
    private final UserManualReference userManualReference;
    private final boolean onlyAffectsCacheableWork;

    public TypeValidationProblem(ValidationProblemId id,
                                 Severity severity,
                                 TypeValidationProblemLocation where,
                                 Supplier<String> shortDescription,
                                 Supplier<String> longDescription,
                                 Supplier<String> reason,
                                 boolean onlyAffectsCacheableWork,
                                 @Nullable UserManualReference userManualReference,
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

    public Optional<UserManualReference> getUserManualReference() {
        return Optional.ofNullable(userManualReference);
    }

    public boolean isOnlyAffectsCacheableWork() {
        return onlyAffectsCacheableWork;
    }
}