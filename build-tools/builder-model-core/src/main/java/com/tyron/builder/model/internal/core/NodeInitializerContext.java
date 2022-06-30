package com.tyron.builder.model.internal.core;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.specs.Specs;
import com.tyron.builder.model.internal.manage.binding.ManagedProperty;
import com.tyron.builder.model.internal.type.ModelType;

public class NodeInitializerContext<T> {
    private final ModelType<T> modelType;
    private final Spec<ModelType<?>> constraints;
    private final Optional<PropertyContext> propertyContextOptional;

    private NodeInitializerContext(ModelType<T> modelType, Spec<ModelType<?>> constraints, Optional<PropertyContext> propertyContextOptional) {
        this.modelType = modelType;
        this.constraints = constraints;
        this.propertyContextOptional = propertyContextOptional;
    }

    public static <T> NodeInitializerContext<T> forType(ModelType<T> type) {
        return new NodeInitializerContext<T>(type, Specs.<ModelType<?>>satisfyAll(), Optional.<PropertyContext>absent());
    }

    public static <T> NodeInitializerContext<T> forExtensibleType(ModelType<T> type, Spec<ModelType<?>> constraints) {
        return new NodeInitializerContext<T>(type, constraints, Optional.<PropertyContext>absent());
    }

    public static <T> NodeInitializerContext<T> forProperty(ModelType<T> type, ManagedProperty<?> property, ModelType<?> containingType) {
        return new NodeInitializerContext<T>(type, Specs.<ModelType<?>>satisfyAll(), Optional.of(new PropertyContext(property.getName(), property.getType(), property.isWritable(), property.isDeclaredAsHavingUnmanagedType(), containingType)));
    }

    public ModelType<T> getModelType() {
        return modelType;
    }

    public Spec<ModelType<?>> getConstraints() {
        return constraints;
    }

    public Optional<PropertyContext> getPropertyContextOptional() {
        return propertyContextOptional;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NodeInitializerContext<?> that = (NodeInitializerContext<?>) o;
        return Objects.equal(modelType, that.modelType);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(modelType);
    }

    public static class PropertyContext {
        private final String name;
        private final ModelType<?> type;
        private final boolean writable;
        private final boolean declaredAsHavingUnmanagedType;
        private final ModelType<?> declaringType;

        private PropertyContext(String name, ModelType<?> type, boolean writable, boolean declaredAsHavingUnmanagedType, ModelType<?> declaringType) {
            this.name = name;
            this.type = type;
            this.writable = writable;
            this.declaredAsHavingUnmanagedType = declaredAsHavingUnmanagedType;
            this.declaringType = declaringType;
        }

        public String getName() {
            return name;
        }

        public ModelType<?> getType() {
            return type;
        }

        public boolean isWritable() {
            return writable;
        }

        public boolean isDeclaredAsHavingUnmanagedType() {
            return declaredAsHavingUnmanagedType;
        }

        public ModelType<?> getDeclaringType() {
            return declaringType;
        }
    }
}
