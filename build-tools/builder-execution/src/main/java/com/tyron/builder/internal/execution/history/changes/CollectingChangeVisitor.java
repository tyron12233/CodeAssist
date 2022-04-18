package com.tyron.builder.internal.execution.history.changes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CollectingChangeVisitor implements ChangeVisitor {
    private List<Change> changes = new ArrayList<Change>();

    @Override
    public boolean visitChange(Change change) {
        changes.add(change);
        return true;
    }

    public Collection<Change> getChanges() {
        return changes;
    }
}