package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

public interface ModelViewFactory<M> {
    ModelView<M> toView(MutableModelNode modelNode, ModelRuleDescriptor ruleDescriptor, boolean mutable);
}
