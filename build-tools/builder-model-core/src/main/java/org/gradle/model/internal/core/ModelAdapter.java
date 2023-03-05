package org.gradle.model.internal.core;

import com.google.common.base.Optional;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.type.ModelType;

import javax.annotation.Nullable;

public interface ModelAdapter {

    @Nullable
    <T> ModelView<? extends T> asImmutable(ModelType<T> type, MutableModelNode node, @Nullable ModelRuleDescriptor ruleDescriptor);

    @Nullable
    <T> ModelView<? extends T> asMutable(ModelType<T> type, MutableModelNode node, ModelRuleDescriptor ruleDescriptor);

    Optional<String> getValueDescription(MutableModelNode mutableModelNode);
}
