package com.tyron.builder.api.internal;

import com.tyron.builder.BuildListener;
import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.internal.plugins.PluginAwareInternal;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.composite.IncludedBuildInternal;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public interface GradleInternal extends Gradle, PluginAwareInternal {

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
     * Returns the broadcaster for {@link ProjectEvaluationListener} events for this build
     */
    ProjectEvaluationListener getProjectEvaluationBroadcaster();

    void setClassLoaderScope(Supplier<? extends ClassLoaderScope> classLoaderScope);

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
     * The basis for project build scripts.
     *
     * It is the Gradle runtime + buildSrc's contributions.
     * This is used as the parent scope for the root project's build script, and all script plugins.
     *
     * This is only on this object for convenience due to legacy.
     * Pre Gradle 6, what is now called {@link SettingsInternal#getBaseClassLoaderScope()} was used as the equivalent scope for project scripts.
     * Since Gradle 6, it does not include buildSrc, whereas this scope does.
     *
     * This method is not named as a property getter to avoid getProperties() invoking it.
     *
     * @throws IllegalStateException if called before {@link #setBaseProjectClassLoaderScope(ClassLoaderScope)}
     */
    ClassLoaderScope baseProjectClassLoaderScope();

    /**
     * @throws IllegalStateException if called more than once
     */
    void setBaseProjectClassLoaderScope(ClassLoaderScope classLoaderScope);

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

    PublicBuildPath getPublicBuildPath();

    // A separate property, as the public getter does not use a wildcard type and cannot be overridden
    List<? extends IncludedBuildInternal> includedBuilds();

    ProjectRegistry<ProjectInternal> getProjectRegistry();

    ClassLoaderScope getClassLoaderScope();

    void setSettings(SettingsInternal settings);

    void setIncludedBuilds(Collection<? extends IncludedBuildInternal> includedBuilds);
}
