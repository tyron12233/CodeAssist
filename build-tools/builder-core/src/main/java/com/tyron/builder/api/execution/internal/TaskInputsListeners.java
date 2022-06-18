package com.tyron.builder.api.execution.internal;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.internal.service.scopes.Scope.Global;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * Allows the registration of {@link TaskInputsListener task inputs listeners}.
 */
@ServiceScope(Global.class)
public interface TaskInputsListeners {

    /**
     * Registers the listener with the build, the listener can be unregistered with {@link #removeListener(TaskInputsListener)}.
     */
    void addListener(TaskInputsListener listener);

    void removeListener(TaskInputsListener listener);

    void broadcastFileSystemInputsOf(TaskInternal task, FileCollectionInternal fileSystemInputs);
}
