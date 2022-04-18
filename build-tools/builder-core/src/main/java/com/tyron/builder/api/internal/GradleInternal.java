package com.tyron.builder.api.internal;

import com.tyron.builder.api.BuildListener;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.util.Path;
import com.tyron.builder.internal.composite.IncludedBuildInternal;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;

public interface GradleInternal extends Gradle {

    @Override
    ProjectInternal getRootProject() throws IllegalStateException;

    @Nullable
    GradleInternal getParent();

    GradleInternal getRoot();

    boolean isRootBuild();

    /**
     * Returns the Gradle user home directory.
     *
     * This directory is used to cache downloaded resources, compiled build scripts and so on.
     *
     * @return The user home directory. Never returns null.
     */
    File getGradleUserHomeDir();

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
     * @return
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

    /**
     * Returns the broadcaster for {@link BuildListener} events
     */
    BuildListener getBuildListenerBroadcaster();

    @Override
    StartParameterInternal getStartParameter();

    ServiceRegistry getServices();

    SettingsInternal getSettings();

    ServiceRegistryFactory getServiceRegistryFactory();

    /**
     * Returns a unique path for this build within the current Gradle invocation.
     */
    Path getIdentityPath();

    String contextualize(String description);

    // A separate property, as the public getter does not use a wildcard type and cannot be overridden
    List<? extends IncludedBuildInternal> includedBuilds();

    ProjectRegistry<ProjectInternal> getProjectRegistry();

    ClassLoaderScope getClassLoaderScope();

    void setSettings(SettingsInternal settings);

    void setIncludedBuilds(Collection<IncludedBuildInternal> children);
}
