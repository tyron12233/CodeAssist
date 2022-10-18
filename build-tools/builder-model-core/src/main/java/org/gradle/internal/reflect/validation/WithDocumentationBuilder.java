package org.gradle.internal.reflect.validation;

public interface WithDocumentationBuilder<T> {
    T withDocumentation(String id, String section);
}