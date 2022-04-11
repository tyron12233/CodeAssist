package com.tyron.builder.api.execution;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.execution.history.InputChangesInternal;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.tasks.TaskOutputsInternal;

import org.jetbrains.annotations.Nullable;

public class TaskExecution {

    private TaskInternal task;
    private TaskExecutionContext context;
    private FileCollectionFactory fileCollectionFactory;
    private FileOperations fileOperations;

    public TaskExecution(
            TaskInternal task,
            TaskExecutionContext context,
            FileCollectionFactory fileCollectionFactory,
            FileOperations fileOperations
    ) {
        this.task = task;
        this.context = context;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileOperations = fileOperations;
    }

    public void execute() {
        FileCollection previousFiles = fileCollectionFactory.empty();
        TaskOutputsInternal outputs = task.getOutputs();
        outputs.setPreviousOutputFiles(previousFiles);
    }

    private void executeActions(TaskInternal task, @Nullable InputChangesInternal inputChanges) {

    }
}
