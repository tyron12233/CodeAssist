package com.tyron.builder.api.internal.reflect.validation;

public interface WithDocumentationBuilder<T> {
    T withDocumentation(String id, String section);
}