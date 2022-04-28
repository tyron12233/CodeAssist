package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.bean.RuntimeBeanNode;
import com.tyron.builder.api.internal.tasks.properties.bean.RuntimeBeanNodeFactory;

import java.util.ArrayDeque;
import java.util.Queue;

public class DefaultPropertyWalker implements PropertyWalker {
    private final RuntimeBeanNodeFactory nodeFactory;

    public DefaultPropertyWalker(TypeMetadataStore typeMetadataStore) {
        this.nodeFactory = new RuntimeBeanNodeFactory(typeMetadataStore);
    }

    @Override
    public void visitProperties(Object bean, TypeValidationContext validationContext, PropertyVisitor visitor) {
        Queue<RuntimeBeanNode<?>> queue = new ArrayDeque<RuntimeBeanNode<?>>();
        queue.add(nodeFactory.createRoot(bean));
        while (!queue.isEmpty()) {
            RuntimeBeanNode<?> node = queue.remove();
            node.visitNode(visitor, queue, nodeFactory, validationContext);
        }
    }
}