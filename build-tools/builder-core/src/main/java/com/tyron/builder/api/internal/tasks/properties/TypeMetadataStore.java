package com.tyron.builder.api.internal.tasks.properties;

public interface TypeMetadataStore {
    <T> TypeMetadata getTypeMetadata(Class<T> type);
}

