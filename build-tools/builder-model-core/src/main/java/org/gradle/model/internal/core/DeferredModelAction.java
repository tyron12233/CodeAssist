package org.gradle.model.internal.core;

import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;

/**
 * An action that should execute early in the lifecycle of a model element, to define rules for the element.
 */
public interface DeferredModelAction {
    ModelRuleDescriptor getDescriptor();

    void execute(MutableModelNode node, ModelActionRole role);
}
