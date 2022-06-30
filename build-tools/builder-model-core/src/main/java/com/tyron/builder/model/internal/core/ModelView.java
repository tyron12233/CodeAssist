package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.type.ModelType;

public interface ModelView<T> {

    ModelPath getPath();

    ModelType<T> getType();

    T getInstance();

    void close();

}