package com.tyron.builder.api.internal.reflect.validation;

import java.util.function.Supplier;

public interface SolutionBuilder extends WithDocumentationBuilder<SolutionBuilder> {
    SolutionBuilder withLongDescription(Supplier<String> description);

    default SolutionBuilder withLongDescription(String longDescription) {
        return withLongDescription(() -> longDescription);
    }
}