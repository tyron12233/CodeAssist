package com.tyron.builder.internal.execution.history.changes;


public interface ChangeContainer {
    /**
     * Propagate changes to the visitor.
     *
     * @return Whether the visitor still wants to obtain more changes.
     */
    boolean accept(ChangeVisitor visitor);
}