package com.tyron.builder.internal.execution.history.changes;

public class ChangeDetectorVisitor implements ChangeVisitor {
    private boolean anyChanges;

    @Override
    public boolean visitChange(Change change) {
        anyChanges = true;
        return false;
    }

    public boolean hasAnyChanges() {
        return anyChanges;
    }
}