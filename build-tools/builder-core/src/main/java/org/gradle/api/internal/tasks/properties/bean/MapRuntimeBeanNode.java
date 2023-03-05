package org.gradle.api.internal.tasks.properties.bean;

import com.google.common.base.Preconditions;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.TypeMetadata;

import java.util.Map;

import java.util.Queue;

class MapRuntimeBeanNode extends RuntimeBeanNode<Map<?, ?>> {
    public MapRuntimeBeanNode(RuntimeBeanNode<?> parentNode, String propertyName, Map<?, ?> map, TypeMetadata typeMetadata) {
        super(parentNode, propertyName, map, typeMetadata);
    }

    @Override
    public void visitNode(PropertyVisitor visitor, Queue<RuntimeBeanNode<?>> queue, RuntimeBeanNodeFactory nodeFactory, TypeValidationContext validationContext) {
        for (Map.Entry<?, ?> entry : getBean().entrySet()) {
            RuntimeBeanNode<?> childNode = createChildNode(
                    Preconditions.checkNotNull(entry.getKey(), "Null keys in nested map '%s' are not allowed.", getPropertyName()).toString(),
                    entry.getValue(),
                    nodeFactory);
            queue.add(childNode);
        }
    }
}