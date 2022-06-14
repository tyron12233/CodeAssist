package com.tyron.builder.model.internal.core;

import com.google.common.base.Optional;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import javax.annotation.Nullable;
import java.util.Collections;

public class EmptyModelProjection implements ModelProjection {

    public static final ModelProjection INSTANCE = new EmptyModelProjection();

    private EmptyModelProjection() {
    }

    @Nullable
    @Override
    public <T> ModelView<? extends T> asImmutable(ModelType<T> type, MutableModelNode node, @Nullable ModelRuleDescriptor ruleDescriptor) {
        return null;
    }

    @Nullable
    @Override
    public <T> ModelView<? extends T> asMutable(ModelType<T> type, MutableModelNode node, ModelRuleDescriptor ruleDescriptor) {
        return null;
    }

    @Override
    public <T> boolean canBeViewedAs(ModelType<T> type) {
        return false;
    }

    @Override
    public Iterable<String> getTypeDescriptions(MutableModelNode node) {
        return Collections.emptyList();
    }

    @Override
    public Optional<String> getValueDescription(MutableModelNode modelNodeInternal) {
        return Optional.absent();
    }

}
