package com.tyron.builder.internal.execution.history.changes;

public interface ChangeVisitor {
    /**
     * Visits a new change.
     *
     * @return Whether to continue looking for changes.
     */
    boolean visitChange(Change change);
}