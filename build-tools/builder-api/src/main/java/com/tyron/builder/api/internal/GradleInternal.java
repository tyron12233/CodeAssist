package com.tyron.builder.api.internal;

import com.tyron.builder.api.Gradle;
import com.tyron.builder.api.internal.build.BuildState;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.Nullable;

public interface GradleInternal extends Gradle {

    @Override
    ProjectInternal getRootProject() throws IllegalStateException;

    @Nullable
    GradleInternal getParent();

    GradleInternal getRoot();

    boolean isRootBuild();

    /**
     * Returns the {@link BuildState} that manages the state of this instance.
     */
    BuildState getOwner();

    /**
     * {@inheritDoc}
     */
    @Override
    TaskExecutionGraphInternal getTaskGraph();

    /**
     * Returns the default project. This is used to resolve relative names and paths provided on the UI.
     */
    ProjectInternal getDefaultProject();

    /**
     * Called by the BuildLoader after the default project is determined.  Until the BuildLoader
     * is executed, {@link #getDefaultProject()} will return null.
     *
     * @param defaultProject The default project for this build.
     */
    void setDefaultProject(ProjectInternal defaultProject);

    /**
     * Called by the BuildLoader after the root project is determined.  Until the BuildLoader
     * is executed, {@link #getRootProject()} will throw {@link IllegalStateException}.
     *
     * @param rootProject The root project for this build.
     */
    void setRootProject(ProjectInternal rootProject);

    ServiceRegistry getServices();

    ServiceRegistryFactory getServiceRegistryFactory();

    /**
     * Returns a unique path for this build within the current Gradle invocation.
     */
    Path getIdentityPath();

    String contextualize(String description);
}
