package org.gradle.model.internal.core;

import org.gradle.api.specs.Spec;
import org.gradle.model.internal.type.ModelType;

public interface ChildNodeInitializerStrategy<T> {

    // Node must project item as S
    <S extends T> NodeInitializer initializer(ModelType<S> type, Spec<ModelType<?>> constraints);

}
