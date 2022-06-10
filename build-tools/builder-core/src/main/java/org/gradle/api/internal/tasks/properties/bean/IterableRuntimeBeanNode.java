package org.gradle.api.internal.tasks.properties.bean;

import org.gradle.api.Named;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.TypeMetadata;

import org.jetbrains.annotations.Nullable;

import java.util.Queue;

class IterableRuntimeBeanNode extends RuntimeBeanNode<Iterable<?>> {
    public IterableRuntimeBeanNode(RuntimeBeanNode<?> parentNode, String propertyName, Iterable<?> iterable, TypeMetadata typeMetadata) {
        super(parentNode, propertyName, iterable, typeMetadata);
    }

    private static String determinePropertyName(@Nullable Object input, int count) {
        String prefix = input instanceof Named ? ((Named) input).getName() : "";
        return prefix + "$" + count;
    }

    @Override
    public void visitNode(PropertyVisitor visitor, Queue<RuntimeBeanNode<?>> queue, RuntimeBeanNodeFactory nodeFactory, TypeValidationContext validationContext) {
        int count = 0;
        for (Object input : getBean()) {
            String propertyName = determinePropertyName(input, count);
            count++;
            queue.add(createChildNode(propertyName, input, nodeFactory));
        }
    }
}