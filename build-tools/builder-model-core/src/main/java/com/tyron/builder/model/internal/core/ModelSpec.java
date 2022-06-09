package com.tyron.builder.model.internal.core;

/**
 * An open set of criteria that selects model nodes. A node must satisfy all of the criteria defined by {@link ModelPredicate} plus
 * those defined by {@link #matches(MutableModelNode)}.
 */
public abstract class ModelSpec extends ModelPredicate {
    /**
     * Returns if the node matches this predicate.
     */
    public boolean matches(MutableModelNode node) {
        return true;
    }
}