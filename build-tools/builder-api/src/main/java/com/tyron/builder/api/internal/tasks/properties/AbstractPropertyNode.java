package com.tyron.builder.api.internal.tasks.properties;

import com.google.common.base.Equivalence;

import org.jetbrains.annotations.Nullable;

public abstract class AbstractPropertyNode<T> {
    private final String propertyName;
    private final AbstractPropertyNode<T> parentNode;
    private final TypeMetadata typeMetadata;

    public AbstractPropertyNode(@Nullable AbstractPropertyNode<T> parentNode, @Nullable String propertyName, TypeMetadata typeMetadata) {
        this.propertyName = propertyName;
        this.parentNode = parentNode;
        this.typeMetadata = typeMetadata;
    }

    protected String getQualifiedPropertyName(String childPropertyName) {
        return propertyName == null ? childPropertyName : propertyName + "." + childPropertyName;
    }

    @Nullable
    public String getPropertyName() {
        return propertyName;
    }

    public TypeMetadata getTypeMetadata() {
        return typeMetadata;
    }

    @Nullable
    protected AbstractPropertyNode<T> findNodeCreatingCycle(T childValue, Equivalence<? super T> nodeEquivalence) {
        if (nodeEquivalence.equivalent(getNodeValue(), childValue)) {
            return this;
        }
        if (parentNode == null) {
            return null;
        }
        return parentNode.findNodeCreatingCycle(childValue, nodeEquivalence);
    }

    abstract protected T getNodeValue();

    @Override
    public String toString() {
        //noinspection ConstantConditions
        return propertyName == null ? "<root>" : getPropertyName();
    }
}