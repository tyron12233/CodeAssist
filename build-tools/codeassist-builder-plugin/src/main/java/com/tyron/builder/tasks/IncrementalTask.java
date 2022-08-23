package com.tyron.builder.tasks;

import org.gradle.api.tasks.TaskAction;
import org.gradle.work.InputChanges;

public abstract class IncrementalTask extends BaseTask {

    @TaskAction
    protected abstract void doTaskAction(InputChanges input);
}
