package com.tyron.builder.model.internal.core;

import javax.annotation.Nullable;

/**
 * A predicate that selects model nodes.
 *
 * <p>Defines a fixed set of criteria that a model node must match. A node is only selected when it matches <em>all</em> non-null criteria.</p>
 */
public abstract class ModelPredicate {
    /**
     * Returns the path of the node to select, or null if path is not relevant.
     *
     * <p>A node will be selected if its path equals the specified path.
     */
    @Nullable
    public ModelPath getPath() {
        return null;
    }

    /**
     * Returns the parent path of the nodes to select, or null if parent is not relevant.
     *
     * <p>A node will be selected if its parent's path equals the specified path.
     */
    @Nullable
    public ModelPath getParent() {
        return null;
    }

    /**
     * Return the path of the scope of the nodes to select, or null if ancestor is not relevant.
     *
     * <p>A node will be selected if its path or one of its ancestor's path equals the specified path.</p>
     */
    @Nullable
    public ModelPath getAncestor() {
        return null;
    }
}