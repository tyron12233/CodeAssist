package com.tyron.builder.api.internal.reflect.validation;

import com.tyron.builder.api.Action;

public interface TypeValidationContext {

    /**
     * Visits a validation problem associated with the given type.
     * Callers are encourages to provide as much information as they can on
     * the problem following the problem builder instructions.
     * @param problemSpec the problem builder
     */
    void visitTypeProblem(Action<? super TypeProblemBuilder> problemSpec);

    /**
     * Visits a validation problem associated with the given property.
     * Callers are encourages to provide as much information as they can on
     * the problem following the problem builder instructions.
     * @param problemSpec the problem builder
     */
    void visitPropertyProblem(Action<? super PropertyProblemBuilder> problemSpec);

    TypeValidationContext NOOP = new TypeValidationContext() {
        @Override
        public void visitTypeProblem(Action<? super TypeProblemBuilder> problemSpec) {}

        @Override
        public void visitPropertyProblem(Action<? super PropertyProblemBuilder> problemSpec) { }
    };

}