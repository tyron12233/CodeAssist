package com.tyron.builder.api.internal.tasks;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

public interface ImplementationAwareTaskAction extends Action<Task> {

    /**
     * Returns the implementation snapshot for the action.
     *
     * This can be the implementation of the implementing class, or of some delegate action.
     */
//    ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher);
}