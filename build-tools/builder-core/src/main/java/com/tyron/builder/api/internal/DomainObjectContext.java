package com.tyron.builder.api.internal;

import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.model.ModelContainer;
import com.tyron.builder.util.Path;

import javax.annotation.Nullable;

/**
 * Represents a position in the tree of builds/projects.
 */
public interface DomainObjectContext {

    /**
     * Creates a path from the root of the build tree to the current context + name.
     */
    Path identityPath(String name);

    /**
     * Creates a path from the root of the project tree to the current context + name.
     */
    Path projectPath(String name);

    /**
     * If this context represents a project, its path.
     */
    @Nullable
    Path getProjectPath();

    /**
     * If this context represents a project, the project.
     */
    @Nullable
    ProjectInternal getProject();

    /**
     * The container that holds the model for this context, to allow synchronized access to the model.
     */
    ModelContainer<?> getModel();

    /**
     * The path to the build that is associated with this object.
     */
    Path getBuildPath();

    /**
     * Whether the context is a script.
     *
     * Some objects are associated with a script, that is associated with a domain object.
     */
    boolean isScript();

    /**
     * Whether the context is a root script.
     *
     * `Settings` is such a context.
     *
     * Some objects are associated with a script, that is associated with a domain object.
     */
    boolean isRootScript();

    /**
     * Indicates if the context is plugin resolution
     */
    boolean isPluginContext();

    /**
     * Returns true if the context represents a detached state, for
     * example detached dependency resolution
     */
    default boolean isDetachedState() {
        return false;
    }
}
