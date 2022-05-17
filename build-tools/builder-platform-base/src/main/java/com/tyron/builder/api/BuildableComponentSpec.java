package com.tyron.builder.api;

import com.tyron.builder.platform.base.ComponentSpec;

import javax.annotation.Nullable;

/**
 * A {@link ComponentSpec} that is directly {@link Buildable} via a specified task.
 */
@Incubating
public interface BuildableComponentSpec extends Buildable, ComponentSpec {
    /**
     * Returns the task responsible for building this component.
     */
    @Nullable
    Task getBuildTask();

    /**
     * Specifies the task responsible for building this component.
     */
    void setBuildTask(@Nullable Task buildTask);

    /**
     * Adds tasks required to build this component. Tasks added this way are subsequently
     * added as dependencies of this component's {@link #getBuildTask() build task}.
     */
    void builtBy(Object... tasks);

    boolean hasBuildDependencies();
}
