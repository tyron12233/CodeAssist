package com.tyron.builder.model.internal.typeregistration;

import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.type.ModelType;

import javax.annotation.Nullable;
import java.util.Set;

public interface InstanceFactory<T> {
    ModelType<T> getBaseInterface();

    Set<ModelType<? extends T>> getSupportedTypes();

    /**
     * Return information about the implementation of an unmanaged type.
     */
    @Nullable
    <S extends T> ImplementationInfo getImplementationInfo(ModelType<S> publicType);

    /**
     * Return information about the implementation of a managed type with an unmanaged super-type.
     */
    <S extends T> ImplementationInfo getManagedSubtypeImplementationInfo(ModelType<S> publicType);

    interface ImplementationInfo {
        /**
         * Creates an instance of the delegate for the given node.
         */
        Object create(MutableModelNode modelNode);

        /**
         * The default implementation type that can be used as a delegate for any managed subtypes of the public type.
         */
        ModelType<?> getDelegateType();

        /**
         * The internal views for the public type.
         */
        Set<ModelType<?>> getInternalViews();
    }
}
