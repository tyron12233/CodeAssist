package com.tyron.builder.api.internal.tasks.properties.bean;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.AbstractPropertyNode;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.TypeMetadata;

import javax.annotation.Nullable;
import java.util.Queue;

public abstract class RuntimeBeanNode<T> extends AbstractPropertyNode<Object> {

    private final T bean;

    protected RuntimeBeanNode(@Nullable RuntimeBeanNode<?> parentNode, @Nullable String propertyName, T bean, TypeMetadata typeMetadata) {
        super(parentNode, propertyName, typeMetadata);
        this.bean = Preconditions.checkNotNull(bean, "Null is not allowed as nested property '%s'", propertyName);
    }

    public T getBean() {
        return bean;
    }

    @Override
    protected Object getNodeValue() {
        return getBean();
    }

    public abstract void visitNode(PropertyVisitor visitor, Queue<RuntimeBeanNode<?>> queue, RuntimeBeanNodeFactory nodeFactory, TypeValidationContext validationContext);

    public RuntimeBeanNode<?> createChildNode(String propertyName, @Nullable Object input, RuntimeBeanNodeFactory nodeFactory) {
        String qualifiedPropertyName = getQualifiedPropertyName(propertyName);
        Object bean = Preconditions.checkNotNull(input, "Null is not allowed as nested property '%s'", qualifiedPropertyName);
        return nodeFactory.create(this, qualifiedPropertyName, bean);
    }

    public void checkCycles(String propertyName, Object childBean) {
        AbstractPropertyNode<?> nodeCreatingCycle = findNodeCreatingCycle(childBean, Equivalence.identity());
        Preconditions.checkState(
                nodeCreatingCycle == null,
                "Cycles between nested beans are not allowed. Cycle detected between: '%s' and '%s'.",
                nodeCreatingCycle, propertyName);
    }
}

