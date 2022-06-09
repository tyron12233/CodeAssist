package com.tyron.builder.model.internal.core;

import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.model.internal.type.ModelType;

public interface ChildNodeInitializerStrategy<T> {

    // Node must project item as S
    <S extends T> NodeInitializer initializer(ModelType<S> type, Spec<ModelType<?>> constraints);

}
