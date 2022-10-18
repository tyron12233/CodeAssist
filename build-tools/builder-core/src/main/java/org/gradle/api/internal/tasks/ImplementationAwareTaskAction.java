package org.gradle.api.internal.tasks;


import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;

public interface ImplementationAwareTaskAction extends Action<Task> {

    /**
     * Returns the implementation snapshot for the action.
     *
     * This can be the implementation of the implementing class, or of some delegate action.
     */
    ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher);
}