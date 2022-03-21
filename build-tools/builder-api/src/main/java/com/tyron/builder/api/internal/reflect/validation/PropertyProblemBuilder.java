package com.tyron.builder.api.internal.reflect.validation;

import org.jetbrains.annotations.Nullable;

public interface PropertyProblemBuilder extends ValidationProblemBuilder<PropertyProblemBuilder> {

    default PropertyProblemBuilder forProperty(String property) {
        return forProperty(null, property);
    }

    PropertyProblemBuilder forProperty(@Nullable String parentProperty, @Nullable String property);

}