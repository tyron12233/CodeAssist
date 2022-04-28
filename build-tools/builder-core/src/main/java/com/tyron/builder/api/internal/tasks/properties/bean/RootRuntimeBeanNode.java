package com.tyron.builder.api.internal.tasks.properties.bean;

import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.TypeMetadata;

import java.util.Queue;

public class RootRuntimeBeanNode extends AbstractNestedRuntimeBeanNode {
    public RootRuntimeBeanNode(Object bean, TypeMetadata typeMetadata) {
        super(null, null, bean, typeMetadata);
    }

    @Override
    public void visitNode(PropertyVisitor visitor, Queue<RuntimeBeanNode<?>> queue, RuntimeBeanNodeFactory nodeFactory, TypeValidationContext validationContext) {
        visitProperties(visitor, queue, nodeFactory, validationContext);
    }
}