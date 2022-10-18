package org.gradle.model.internal.core;

import com.google.common.base.Optional;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.type.ModelType;

public class TypedModelProjection<M> extends TypeCompatibilityModelProjectionSupport<M> {

    private final ModelViewFactory<M> viewFactory;

    public static <M> ModelProjection of(ModelType<M> type, ModelViewFactory<M> viewFactory) {
        return new TypedModelProjection<M>(type, viewFactory);
    }

    public TypedModelProjection(ModelType<M> type, ModelViewFactory<M> viewFactory) {
        super(type);
        this.viewFactory = viewFactory;
    }

    @Override
    public Optional<String> getValueDescription(MutableModelNode modelNodeInternal) {
        return Optional.absent();
    }

    @Override
    protected ModelView<M> toView(MutableModelNode modelNode, ModelRuleDescriptor ruleDescriptor, boolean writable) {
        return viewFactory.toView(modelNode, ruleDescriptor, writable);
    }
}
