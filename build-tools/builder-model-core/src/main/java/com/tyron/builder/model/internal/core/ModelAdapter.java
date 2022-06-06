package com.tyron.builder.model.internal.core;

import com.google.common.base.Optional;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

import javax.annotation.Nullable;

public interface ModelAdapter {

    @Nullable
    <T> ModelView<? extends T> asImmutable(ModelType<T> type, MutableModelNode node, @Nullable ModelRuleDescriptor ruleDescriptor);

    @Nullable
    <T> ModelView<? extends T> asMutable(ModelType<T> type, MutableModelNode node, ModelRuleDescriptor ruleDescriptor);

    Optional<String> getValueDescription(MutableModelNode mutableModelNode);
}
