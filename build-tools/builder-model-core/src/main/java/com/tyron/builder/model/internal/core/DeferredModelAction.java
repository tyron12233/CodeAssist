package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

/**
 * An action that should execute early in the lifecycle of a model element, to define rules for the element.
 */
public interface DeferredModelAction {
    ModelRuleDescriptor getDescriptor();

    void execute(MutableModelNode node, ModelActionRole role);
}
