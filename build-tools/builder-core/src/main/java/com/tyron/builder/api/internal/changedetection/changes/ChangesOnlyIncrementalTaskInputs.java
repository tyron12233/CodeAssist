package com.tyron.builder.api.internal.changedetection.changes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;

import java.util.ArrayList;
import java.util.List;

public class ChangesOnlyIncrementalTaskInputs extends StatefulIncrementalTaskInputs {
    private final Iterable<InputFileDetails> inputFilesState;
    private final List<InputFileDetails> removedFiles = new ArrayList<InputFileDetails>();

    public ChangesOnlyIncrementalTaskInputs(Iterable<InputFileDetails> inputFilesState) {
        this.inputFilesState = inputFilesState;
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    protected void doOutOfDate(final Action<? super InputFileDetails> outOfDateAction) {
        for (InputFileDetails fileChange : inputFilesState) {
            if (fileChange.isRemoved()) {
                removedFiles.add(fileChange);
            } else {
                outOfDateAction.execute(fileChange);
            }
        }
    }

    @Override
    protected void doRemoved(Action<? super InputFileDetails> removedAction) {
        for (InputFileDetails removedFile : removedFiles) {
            removedAction.execute(removedFile);
        }
    }
}