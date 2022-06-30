package com.tyron.builder.model.internal.core;

import com.google.common.collect.Multimap;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

/**
 * A standalone strategy for initializing a node.
 * <p>
 * Differs from {@link ModelRegistration} in that it's more of a template for a creator.
 * It does not say anything about the actual entity (e.g. its path) or the identity of the creation rule.
 *
 * @see ModelRegistrations
 */
public interface NodeInitializer {

    Multimap<ModelActionRole, ModelAction> getActions(ModelReference<?> subject, ModelRuleDescriptor descriptor);

}
