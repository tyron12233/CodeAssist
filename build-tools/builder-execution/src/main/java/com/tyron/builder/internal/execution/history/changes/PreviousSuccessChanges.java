package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.internal.execution.history.DescriptiveChange;

public class PreviousSuccessChanges implements ChangeContainer {
    private static final Change PREVIOUS_FAILURE = new DescriptiveChange("Task has failed previously.");

    private final boolean successful;

    public PreviousSuccessChanges(boolean successful) {
        this.successful = successful;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        if (!successful) {
            return visitor.visitChange(PREVIOUS_FAILURE);
        }
        return true;
    }
}