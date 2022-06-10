package org.gradle.api.internal.tasks.properties;

public interface TypeMetadataStore {
    <T> TypeMetadata getTypeMetadata(Class<T> type);
}

