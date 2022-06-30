package com.tyron.builder.api.execution.internal;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scope.Global;

/**
 * Registered via {@link TaskInputsListeners}.
 */
@EventScope(Global.class)
public interface TaskInputsListener {

    /**
     * Called when the execution of the given task is imminent, or would have been if the given file collection was not currently empty.
     * <p>
     * The given files may not == taskInternal.inputs.files, as only a subset of that collection may be relevant to the task execution.
     *
     * @param task the task to be executed
     * @param fileSystemInputs the file system inputs relevant to the task execution
     */
    void onExecute(TaskInternal task, FileCollectionInternal fileSystemInputs);

}
