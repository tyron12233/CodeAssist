package com.tyron.builder.api.tasks;

/**
 * A {@code Buildable} represents an artifact or set of artifacts which are built by one or more {@link Task}
 * instances.
 */
public interface Buildable {
    /**
     * Returns a dependency which contains the tasks which build this artifact. All {@code Buildable} implementations
     * must ensure that the returned dependency object is live, so that it tracks changes to the dependencies of this
     * buildable.
     *
     * @return The dependency. Never returns null. Returns an empty dependency when this artifact is not built by any
     *         tasks.
     */
    TaskDependency getBuildDependencies();
}