package com.tyron.builder.invocation;

import com.google.common.collect.ImmutableList;
import groovy.lang.Closure;
import com.tyron.builder.BuildListener;
import com.tyron.builder.BuildResult;
import com.tyron.builder.StartParameter;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.UnknownDomainObjectException;
import com.tyron.builder.api.initialization.IncludedBuild;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.BuildScopeListenerRegistrationListener;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.MutationGuards;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerFactory;
import com.tyron.builder.api.internal.plugins.DefaultObjectConfigurationAction;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.api.internal.project.AbstractPluginAware;
import com.tyron.builder.api.internal.project.CrossProjectConfigurator;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.configuration.ScriptPluginFactory;
import com.tyron.builder.configuration.internal.ListenerBuildOperationDecorator;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.initialization.ClassLoaderScopeRegistry;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.InternalBuildAdapter;
import com.tyron.builder.internal.MutableActionSet;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.composite.IncludedBuildInternal;
import com.tyron.builder.internal.event.ListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.installation.CurrentGradleInstallation;
import com.tyron.builder.internal.installation.GradleInstallation;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.listener.ClosureBackedMethodInvocationDispatch;
import com.tyron.builder.util.GradleVersion;
import com.tyron.builder.util.Path;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public abstract class DefaultGradle extends AbstractPluginAware implements GradleInternal {

    private SettingsInternal settings;
    private ProjectInternal rootProject;
    private ProjectInternal defaultProject;
    private final BuildState parent;
    private final StartParameter startParameter;
    private final ServiceRegistry services;
    private final ListenerBroadcast<BuildListener> buildListenerBroadcast;
    private final ListenerBroadcast<ProjectEvaluationListener> projectEvaluationListenerBroadcast;
    private final CrossProjectConfigurator crossProjectConfigurator;
    private List<IncludedBuildInternal> includedBuilds;
    private final MutableActionSet<BuildProject> rootProjectActions = new MutableActionSet<BuildProject>();
    private boolean projectsLoaded;
    private Path identityPath;
    private Supplier<? extends ClassLoaderScope> classLoaderScope;
    private ClassLoaderScope baseProjectClassLoaderScope;

    public DefaultGradle(@Nullable BuildState parent, StartParameter startParameter, ServiceRegistryFactory parentRegistry) {
        this.parent = parent;
        this.startParameter = startParameter;
        this.services = parentRegistry.createFor(this);
        this.crossProjectConfigurator = services.get(CrossProjectConfigurator.class);
        buildListenerBroadcast = getListenerManager().createAnonymousBroadcaster(BuildListener.class);
        projectEvaluationListenerBroadcast = getListenerManager().createAnonymousBroadcaster(ProjectEvaluationListener.class);

        buildListenerBroadcast.add(new InternalBuildAdapter() {
            @Override
            public void projectsLoaded(Gradle gradle) {
                if (!rootProjectActions.isEmpty()) {
                    services.get(CrossProjectConfigurator.class).rootProject(rootProject, rootProjectActions);
                }
                projectsLoaded = true;
            }
        });

//        if (parent == null) {
//            services.get(GradleEnterprisePluginManager.class).registerMissingPluginWarning(this);
//        }
    }

    @Override
    public String toString() {
        return rootProject == null ? "build" : ("build '" + rootProject.getName() + "'");
    }

    @Override
    public Path getIdentityPath() {
        if (identityPath == null) {
            identityPath = services.get(PublicBuildPath.class).getBuildPath();
        }
        return identityPath;
    }

    @Override
    public String contextualize(String description) {
        if (isRootBuild()) {
            return description;
        } else {
            Path contextPath = getIdentityPath();
            String context = contextPath == null ? getStartParameter().getCurrentDir().getName() : contextPath.getPath();
            return description + " (" + context + ")";
        }
    }

    @Override
    public GradleInternal getParent() {
        return parent == null ? null : parent.getMutableModel();
    }

    @Override
    public GradleInternal getRoot() {
        GradleInternal parent = getParent();
        if (parent == null) {
            return this;
        } else {
            return parent.getRoot();
        }
    }

    @Override
    public boolean isRootBuild() {
        return parent == null;
    }

    @Override
    public BuildState getOwner() {
        return getServices().get(BuildState.class);
    }

    @Override
    public String getGradleVersion() {
        return GradleVersion.current().getVersion();
    }

    @Override
    public File getGradleHomeDir() {
        GradleInstallation gradleInstallation = getCurrentGradleInstallation().getInstallation();
        return gradleInstallation == null ? null : gradleInstallation.getGradleHome();
    }

    @Override
    public File getGradleUserHomeDir() {
        return startParameter.getGradleUserHomeDir();
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return (StartParameterInternal) startParameter;
    }

    @Override
    public ClassLoaderScope baseProjectClassLoaderScope() {
        if (baseProjectClassLoaderScope == null) {
            throw new IllegalStateException("baseProjectClassLoaderScope not yet set");
        }
        return baseProjectClassLoaderScope;
    }

    @Override
    public void setBaseProjectClassLoaderScope(ClassLoaderScope classLoaderScope) {
        if (classLoaderScope == null) {
            throw new IllegalArgumentException("classLoaderScope must not be null");
        }
        if (baseProjectClassLoaderScope != null) {
            throw new IllegalStateException("baseProjectClassLoaderScope is already set");
        }

        this.baseProjectClassLoaderScope = classLoaderScope;
    }

    @Override
    public SettingsInternal getSettings() {
        if (settings == null) {
            throw new IllegalStateException("The settings are not yet available for " + this + ".");
        }
        return settings;
    }

    @Override
    public void setSettings(SettingsInternal settings) {
        this.settings = settings;
    }

    @Override
    public ProjectInternal getRootProject() {
        if (rootProject == null) {
            throw new IllegalStateException("The root project is not yet available for " + this + ".");
        }
        return rootProject;
    }

    @Override
    public void setRootProject(ProjectInternal rootProject) {
        this.rootProject = rootProject;
    }

    @Override
    public void rootProject(Action<? super BuildProject> action) {
        rootProject("Gradle.rootProject", action);
    }

    private void rootProject(String registrationPoint, Action<? super BuildProject> action) {
        if (projectsLoaded) {
            assert rootProject != null;
            action.execute(rootProject);
        } else {
            // only need to decorate when this callback is delayed
            rootProjectActions.add(getListenerBuildOperationDecorator().decorate(registrationPoint, action));
        }
    }

    @Override
    public void allprojects(final Action<? super BuildProject> action) {
        rootProject("Gradle.allprojects", new Action<BuildProject>() {
            @Override
            public void execute(BuildProject project) {
                project.allprojects(action);
            }
        });
    }

    @Override
    public ProjectInternal getDefaultProject() {
        if (defaultProject == null) {
            throw new IllegalStateException("The default project is not yet available for " + this + ".");
        }
        return defaultProject;
    }

    @Override
    public void setDefaultProject(ProjectInternal defaultProject) {
        this.defaultProject = defaultProject;
    }

    @Inject
    @Override
    public abstract TaskExecutionGraphInternal getTaskGraph();

    @Override
    public ProjectEvaluationListener addProjectEvaluationListener(ProjectEvaluationListener listener) {
        addListener("Gradle.addProjectEvaluationListener", listener);
        return listener;
    }

    @Override
    public void removeProjectEvaluationListener(ProjectEvaluationListener listener) {
        removeListener(listener);
    }

    private void assertProjectMutatingMethodAllowed(String methodName) {
        MutationGuards.of(crossProjectConfigurator).assertMutationAllowed(methodName, this, Gradle.class);
    }

    @Override
    public void beforeProject(Closure closure) {
        assertProjectMutatingMethodAllowed("beforeProject(Closure)");
        projectEvaluationListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("beforeEvaluate", getListenerBuildOperationDecorator().decorate("Gradle.beforeProject", Cast.<Closure<?>>uncheckedNonnullCast(closure))));
    }

    @Override
    public void beforeProject(Action<? super BuildProject> action) {
        assertProjectMutatingMethodAllowed("beforeProject(Action)");
        projectEvaluationListenerBroadcast.add("beforeEvaluate", getListenerBuildOperationDecorator().decorate("Gradle.beforeProject", action));
    }

    @Override
    public void afterProject(Closure closure) {
        assertProjectMutatingMethodAllowed("afterProject(Closure)");
        projectEvaluationListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("afterEvaluate", getListenerBuildOperationDecorator().decorate("Gradle.afterProject", Cast.<Closure<?>>uncheckedNonnullCast(closure))));
    }

    @Override
    public void afterProject(Action<? super BuildProject> action) {
        assertProjectMutatingMethodAllowed("afterProject(Action)");
        projectEvaluationListenerBroadcast.add("afterEvaluate", getListenerBuildOperationDecorator().decorate("Gradle.afterProject", action));
    }

    @Override
    public void beforeSettings(Closure<?> closure) {
        buildListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("beforeSettings", closure));
    }

    @Override
    public void beforeSettings(Action<? super Settings> action) {
        buildListenerBroadcast.add("beforeSettings", action);
    }

    @Override
    public void settingsEvaluated(Closure closure) {
        buildListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("settingsEvaluated", closure));
    }

    @Override
    public void settingsEvaluated(Action<? super Settings> action) {
        buildListenerBroadcast.add("settingsEvaluated", action);
    }

    @Override
    public void projectsLoaded(Closure closure) {
        assertProjectMutatingMethodAllowed("projectsLoaded(Closure)");
        buildListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("projectsLoaded", getListenerBuildOperationDecorator().decorate("Gradle.projectsLoaded", Cast.<Closure<?>>uncheckedNonnullCast(closure))));
    }

    @Override
    public void projectsLoaded(Action<? super Gradle> action) {
        assertProjectMutatingMethodAllowed("projectsLoaded(Action)");
        buildListenerBroadcast.add("projectsLoaded", getListenerBuildOperationDecorator().decorate("Gradle.projectsLoaded", action));
    }

    @Override
    public void projectsEvaluated(Closure closure) {
        assertProjectMutatingMethodAllowed("projectsEvaluated(Closure)");
        buildListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("projectsEvaluated", getListenerBuildOperationDecorator().decorate("Gradle.projectsEvaluated", Cast.<Closure<?>>uncheckedNonnullCast(closure))));
    }

    @Override
    public void projectsEvaluated(Action<? super Gradle> action) {
        assertProjectMutatingMethodAllowed("projectsEvaluated(Action)");
        buildListenerBroadcast.add("projectsEvaluated", getListenerBuildOperationDecorator().decorate("Gradle.projectsEvaluated", action));
    }

    @Override
    public void buildFinished(Closure closure) {
        notifyListenerRegistration("Gradle.buildFinished", closure);
        buildListenerBroadcast.add(new ClosureBackedMethodInvocationDispatch("buildFinished", closure));
    }

    @Override
    public void buildFinished(Action<? super BuildResult> action) {
        notifyListenerRegistration("Gradle.buildFinished", action);
        buildListenerBroadcast.add("buildFinished", action);
    }

    @Override
    public void addListener(Object listener) {
        addListener("Gradle.addListener", listener);
    }

    private void addListener(String registrationPoint, Object listener) {
        notifyListenerRegistration(registrationPoint, listener);
        getListenerManager().addListener(getListenerBuildOperationDecorator().decorateUnknownListener(registrationPoint, listener));
    }

    private void notifyListenerRegistration(String registrationPoint, Object listener) {
        getListenerManager().getBroadcaster(BuildScopeListenerRegistrationListener.class)
                .onBuildScopeListenerRegistration(listener, registrationPoint, this);
    }

    @Override
    public void removeListener(Object listener) {
        // do same decoration as in addListener to remove correctly
        getListenerManager().removeListener(getListenerBuildOperationDecorator().decorateUnknownListener(null, listener));
    }

    @Override
    public void useLogger(Object logger) {
        getListenerManager().useLogger(logger);
    }

    @Override
    public ProjectEvaluationListener getProjectEvaluationBroadcaster() {
        return projectEvaluationListenerBroadcast.getSource();
    }

    @Override
    public void addBuildListener(BuildListener buildListener) {
        addListener("Gradle.addBuildListener", buildListener);
    }

    @Override
    public BuildListener getBuildListenerBroadcaster() {
        return buildListenerBroadcast.getSource();
    }

    @Override
    public Gradle getGradle() {
        return this;
    }

//    @Override
//    @Inject
//    public abstract BuildServiceRegistry getSharedServices();

    @Override
    public Collection<IncludedBuild> getIncludedBuilds() {
        return Cast.uncheckedCast(includedBuilds());
    }
//
    @Override
    public void setIncludedBuilds(Collection<? extends IncludedBuildInternal> includedBuilds) {
        this.includedBuilds = ImmutableList.copyOf(includedBuilds);
    }

    @Override
    public List<? extends IncludedBuildInternal> includedBuilds() {
        if (includedBuilds == null) {
            throw new IllegalStateException("Included builds are not yet available for this build.");
        }
        return includedBuilds;
    }

    @Override
    public IncludedBuild includedBuild(final String name) {
        for (IncludedBuild includedBuild : includedBuilds()) {
            if (includedBuild.getName().equals(name)) {
                return includedBuild;
            }
        }
        throw new UnknownDomainObjectException("Included build '" + name + "' not found in " + this + ".");
    }

    @Override
    public ServiceRegistry getServices() {
        return services;
    }

    @Override
    @Inject
    public ServiceRegistryFactory getServiceRegistryFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected DefaultObjectConfigurationAction createObjectConfigurationAction() {
        return new DefaultObjectConfigurationAction(
                getFileResolver(),
                getScriptPluginFactory(),
                getScriptHandlerFactory(),
                getClassLoaderScope(),
                getResourceLoaderFactory(),
                this
        );
    }

    @Override
    public void setClassLoaderScope(Supplier<? extends ClassLoaderScope> classLoaderScope) {
        if (this.classLoaderScope != null) {
            throw new IllegalStateException("Class loader scope already used");
        }
        this.classLoaderScope = classLoaderScope;
    }

    @Override
    public ClassLoaderScope getClassLoaderScope() {
        if (classLoaderScope == null) {
            classLoaderScope = () -> getClassLoaderScopeRegistry().getCoreAndPluginsScope();
        }
        return classLoaderScope.get();
    }

    @Inject
    protected abstract ClassLoaderScopeRegistry getClassLoaderScopeRegistry();

    @Override
    @Inject
    public abstract ProjectRegistry<ProjectInternal> getProjectRegistry();

    @Inject
    protected abstract TextUriResourceLoader.Factory getResourceLoaderFactory();

    @Inject
    protected abstract ScriptHandlerFactory getScriptHandlerFactory();

    @Inject
    protected abstract ScriptPluginFactory getScriptPluginFactory();

    @Inject
    protected abstract FileResolver getFileResolver();

    @Inject
    protected abstract CurrentGradleInstallation getCurrentGradleInstallation();

    @Inject
    protected abstract ListenerManager getListenerManager();

    @Inject
    protected abstract ListenerBuildOperationDecorator getListenerBuildOperationDecorator();

    @Override
    @Inject
    public abstract PluginManagerInternal getPluginManager();

    @Override
    @Inject
    public abstract PublicBuildPath getPublicBuildPath();
}
