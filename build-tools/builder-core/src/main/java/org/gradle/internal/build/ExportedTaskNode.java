package org.gradle.internal.build;

import org.gradle.api.internal.TaskInternal;
import org.gradle.composite.internal.IncludedBuildTaskResource;

/**
 * A node in a build's work graph that can be referenced by the work graph of another build.
 */
public interface ExportedTaskNode {
    TaskInternal getTask();

    IncludedBuildTaskResource.State getTaskState();
}
