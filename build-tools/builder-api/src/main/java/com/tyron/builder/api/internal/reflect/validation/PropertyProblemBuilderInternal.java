package com.tyron.builder.api.internal.reflect.validation;

import org.jetbrains.annotations.Nullable;

/**
 * This interface defines methods which shouldn't be called by developers
 * of new validation problems, but only internally used by the validation
 * system, for example to remap nested properties to an owner.
 */
public interface PropertyProblemBuilderInternal extends PropertyProblemBuilder {
    /**
     * This method is called whenever we have nested types, that we're "replaying"
     * validation for those nested types, and that we want the actual property
     * to be reported as the parent.nested property name.
     */
    PropertyProblemBuilder forOwner(@Nullable String parentProperty);

    /**
     * Declares the root type for this property problem
     */
    PropertyProblemBuilder forType(@Nullable Class<?> rootType);
}