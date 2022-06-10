package org.gradle.model.internal.core;

import org.gradle.model.internal.type.ModelType;

public interface ModelView<T> {

    ModelPath getPath();

    ModelType<T> getType();

    T getInstance();

    void close();

}