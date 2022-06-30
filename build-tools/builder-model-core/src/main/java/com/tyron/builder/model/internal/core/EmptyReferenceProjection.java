package com.tyron.builder.model.internal.core;

import com.google.common.base.Optional;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

/**
 * Projection used to resolve the type of a reference node when the target is {@code null}.
 */
public class EmptyReferenceProjection<T> extends TypeCompatibilityModelProjectionSupport<T> {
    public EmptyReferenceProjection(ModelType<T> type) {
        super(type);
    }

    @Override
    protected ModelView<T> toView(MutableModelNode modelNode, ModelRuleDescriptor ruleDescriptor, boolean writable) {
        return InstanceModelView.of(modelNode.getPath(), getType(), null);
    }

    @Override
    public Optional<String> getValueDescription(MutableModelNode mutableModelNode) {
        return Optional.absent();
    }
}
