package org.gradle.model.internal.core;

import com.google.common.collect.Multimap;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;

public interface ModelRegistration {
    ModelRuleDescriptor getDescriptor();

    ModelPath getPath();

    /**
     * Actions that need to be registered when the node is registered.
     */
    Multimap<ModelActionRole, ? extends ModelAction> getActions();

    /**
     * Returns whether the registered node is hidden.
     */
    boolean isHidden();
}