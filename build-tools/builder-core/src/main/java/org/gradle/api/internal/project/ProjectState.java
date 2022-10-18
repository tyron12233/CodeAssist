package org.gradle.api.internal.project;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.internal.DisplayName;
import org.gradle.api.Project;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.util.Path;
import org.gradle.internal.model.ModelContainer;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;

public interface ProjectState extends ModelContainer<ProjectInternal> {

    DisplayName getDisplayName();

    /**
     * Returns the containing build of this project.
     */
    BuildState getOwner();

    /**
     * Returns the parent of this project in the project tree. Note that this is not the same as {@link Project#getParent()}, use {@link #getBuildParent()} for that.
     */
    @Nullable
    ProjectState getParent();

    /**
     * Returns the parent of this project, as per {@link Project#getParent()}. This will be null for the root project of a build in the tree, even if the project is not
     * at the root of the project tree.
     */
    @Nullable
    ProjectState getBuildParent();

    /**
     * Returns the direct children of this project, in public iteration order.
     */
    Set<ProjectState> getChildProjects();

    /**
     * Returns the name of this project (which may not necessarily be unique).
     */
    String getName();

    /**
     * Returns an identifying path for this project in the build tree.
     */
    Path getIdentityPath();

    /**
     * Returns a path for this project within its containing build. These are not unique within a build tree.
     */
    Path getProjectPath();

    /**
     * Returns the project directory.
     */
    File getProjectDir();

    /**
     * Returns the identifier of the default component produced by this project.
     */
    ProjectComponentIdentifier getComponentIdentifier();

    /**
     * Configures the mutable model for this project, if not already.
     *
     * May also configure the parent of this project.
     */
    void ensureConfigured();

    /**
     * Configure the mutable model for this project and discovers any registered tasks, if not already done.
     */
    void ensureTasksDiscovered();

    /**
     * Returns the mutable model for this project. This should not be used directly. This property is here to help with migration away from direct usage.
     */
    ProjectInternal getMutableModel();

    /**
     * Creates the mutable model for this project.
     */
    void createMutableModel(ClassLoaderScope selfClassLoaderScope, ClassLoaderScope baseClassLoaderScope);

    /**
     * Returns the lock that will be acquired when accessing the mutable state of this project via {@link #applyToMutableState(Consumer)} and {@link #fromMutableState(Function)}.
     * A caller can optionally acquire this lock before calling one of these accessor methods, in order to avoid those methods blocking.
     *
     * <p>When parallel execution is disabled, the lock is shared between projects within a build, and each build in the build tree has its own shared lock.
     */
    ResourceLock getAccessLock();

    /**
     * Returns the lock that should be acquired by non-isolated tasks from this project prior to execution.
     *
     * <p>This lock allows both access to the project state and the right to execute as a task. Acquiring this lock also acquires the lock returned by {@link #getAccessLock()}.
     *
     * <p>When a task reaches across project boundaries, the project state lock is released but the task execution lock is not. This prevents other tasks from the same project from starting but
     * allows tasks in other projects to access the state of this project without deadlocks in cases where there are dependency cycles between projects. It also allows other non-taask work
     * to run while the task is blocked.
     *
     * <p>When parallel execution is not enabled, the lock is shared between projects within a build, and each build in the build tree has its own shared lock.
     */
    ResourceLock getTaskExecutionLock();
}
