package com.tyron.builder.api.internal.reflect.validation;

import com.tyron.builder.api.Action;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class ReplayingTypeValidationContext implements TypeValidationContext {
    private final List<BiConsumer<String, TypeValidationContext>> problems = new ArrayList<>();

    @Override
    public void visitTypeProblem(Action<? super TypeProblemBuilder> problemSpec) {
        problems.add((ownerProperty, validationContext) -> validationContext.visitTypeProblem(problemSpec));
    }

    @Override
    public void visitPropertyProblem(Action<? super PropertyProblemBuilder> problemSpec) {
        problems.add((ownerProperty, validationContext) -> validationContext.visitPropertyProblem(builder -> {
            problemSpec.execute(builder);
            ((PropertyProblemBuilderInternal)builder).forOwner(ownerProperty);
        }));
    }

    public void replay(@Nullable String ownerProperty, TypeValidationContext target) {
        problems.forEach(problem -> problem.accept(ownerProperty, target));
    }
}