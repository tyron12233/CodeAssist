package com.tyron.builder.api.internal.changedetection.changes;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;

@SuppressWarnings("deprecation")
public abstract class StatefulIncrementalTaskInputs implements IncrementalTaskInputs {
    private boolean outOfDateProcessed;
    private boolean removedProcessed;

    @Override
    public void outOfDate(final Action<? super InputFileDetails> outOfDateAction) {
        if (outOfDateProcessed) {
            throw new IllegalStateException("Cannot process outOfDate files multiple times");
        }
        doOutOfDate(outOfDateAction);
        outOfDateProcessed = true;
    }

    protected abstract void doOutOfDate(Action<? super InputFileDetails> outOfDateAction);

    @Override
    public void removed(Action<? super InputFileDetails> removedAction) {
        if (!outOfDateProcessed) {
            throw new IllegalStateException("Must first process outOfDate files before processing removed files");
        }
        if (removedProcessed) {
            throw new IllegalStateException("Cannot process removed files multiple times");
        }
        doRemoved(removedAction);
        removedProcessed = true;
    }

    protected abstract void doRemoved(Action<? super InputFileDetails> removedAction);
}