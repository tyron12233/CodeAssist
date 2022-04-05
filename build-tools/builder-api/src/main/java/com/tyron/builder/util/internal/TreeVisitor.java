package com.tyron.builder.util.internal;

/**
 * Visits a tree with nodes of type T.
 */
public class TreeVisitor<T> {
    /**
     * Visits a node of the tree.
     */
    public void node(T node) {
    }

    /**
     * Starts visiting the children of the most recently visited node.
     */
    public void startChildren() {
    }

    /**
     * Finishes visiting the children of the most recently started node.
     */
    public void endChildren() {
    }
}
