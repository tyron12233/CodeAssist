package com.tyron.builder.tasks;

import com.tyron.builder.gradle.internal.tasks.AndroidVariantTask;
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTaskKt;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.InputChanges;

public abstract class IncrementalTask extends AndroidVariantTask {

    @TaskAction
    public void taskAction(InputChanges input) {
        if (!input.isIncremental()) {
            NonIncrementalTaskKt.cleanUpTaskOutputs(this);
        }
        doTaskAction(input);
    }

    protected abstract void doTaskAction(InputChanges input);
}
