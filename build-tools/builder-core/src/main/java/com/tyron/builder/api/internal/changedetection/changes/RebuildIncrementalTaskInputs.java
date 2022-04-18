package com.tyron.builder.api.internal.changedetection.changes;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;

public class RebuildIncrementalTaskInputs extends StatefulIncrementalTaskInputs {
    private final Iterable<InputFileDetails> inputChanges;

    public RebuildIncrementalTaskInputs(Iterable<InputFileDetails> inputChanges) {
        this.inputChanges = inputChanges;
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void doOutOfDate(final Action<? super InputFileDetails> outOfDateAction) {
        for (InputFileDetails inputFileChange : inputChanges) {
            outOfDateAction.execute(inputFileChange);
        }
    }

    @Override
    public void doRemoved(Action<? super InputFileDetails> removedAction) {
    }
}