package org.gradle.api.internal.tasks.properties.bean;

import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.TypeMetadata;

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