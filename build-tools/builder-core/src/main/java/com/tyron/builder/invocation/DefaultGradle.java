package com.tyron.builder.invocation;

import com.tyron.builder.api.Action;
import com.tyron.builder.BuildListener;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.StartParameter;
import com.tyron.builder.api.initialization.IncludedBuild;
import com.tyron.builder.initialization.ClassLoaderScopeRegistry;
import com.tyron.builder.internal.event.ListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.project.DefaultProjectRegistry;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.composite.IncludedBuildInternal;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public class DefaultGradle implements GradleInternal {

    private SettingsInternal settings;
    private final BuildState parent;
    private final ServiceRegistry services;
    private final ServiceRegistryFactory serviceRegistryFactory;
    private final StartParameter startParameter;
    private ProjectInternal rootProject;
    private ProjectInternal defaultProject;
    private Path identityPath;
    private boolean projectsLoaded;
    private ArrayList<IncludedBuild> includedBuilds;
    private final ListenerBroadcast<BuildListener> buildListenerBroadcast;
    private Supplier<? extends ClassLoaderScope> classLoaderScope;
    private ClassLoaderScope baseProjectClassLoaderScope;

    public DefaultGradle(@Nullable BuildState parent, StartParameter startParameter, ServiceRegistryFactory parentRegistry) {
        this.parent = parent;
        this.startParameter = startParameter;
        this.serviceRegistryFactory = parentRegistry;
        this.services = parentRegistry.createFor(this);
//        this.crossProjectConfigurator = services.get(CrossProjectConfigurator.class);
        buildListenerBroadcast = getListenerManager().createAnonymousBroadcaster(BuildListener.class);
//        projectEvaluationListenerBroadcast = getListenerManager().createAnonymousBroadcaster(ProjectEvaluationListener.class);

//        buildListenerBroadcast.add(new InternalBuildAdapter() {
//            @Override
//            public void projectsLoaded(Gradle gradle) {
//                if (!rootProjectActions.isEmpty()) {
//                    services.get(CrossProjectConfigurator.class).rootProject(rootProject, rootProjectActions);
//                }
//                projectsLoaded = true;
//            }
//        });
        projectsLoaded = true;

//        if (parent == null) {
//            services.get(GradleEnterprisePluginManager.class).registerMissingPluginWarning(this);
//        }
    }

    private ListenerManager getListenerManager() {
        return services.get(ListenerManager.class);
    }

    @Override
    public String toString() {
        return rootProject == null ? "build" : ("build '" + rootProject
                .getName() + "'");
    }

    @Override
    public Path getIdentityPath() {
        if (identityPath == null) {
            identityPath = Path.ROOT;
        }
        return identityPath;
    }

    @Override
    public String contextualize(String description) {
        if (isRootBuild()) {
            return description;
        } else {
            Path contextPath = getIdentityPath();
            String context = contextPath == null ? "getStartParameter().getCurrentDir().getName()" : contextPath.getPath();
            return description + " (" + context + ")";
        }
    }

    @Override
    public List<? extends IncludedBuildInternal> includedBuilds() {
        return null;
    }

    @Override
    public ProjectRegistry<ProjectInternal> getProjectRegistry() {
        //noinspection unchecked
        return services.get(DefaultProjectRegistry.class);
    }

    @Override
    public ClassLoaderScope getClassLoaderScope() {
        if (classLoaderScope == null) {
            classLoaderScope = () -> getClassLoaderScopeRegistry().getCoreAndPluginsScope();
        }
        return classLoaderScope.get();
    }

    protected ClassLoaderScopeRegistry getClassLoaderScopeRegistry() {
        return services.get(ClassLoaderScopeRegistry.class);
    }

    @Override
    public void setSettings(SettingsInternal settings) {
        this.settings = settings;
    }

    @Override
    public void setIncludedBuilds(Collection<IncludedBuildInternal> children) {
        includedBuilds = new ArrayList<>(children);
    }


    @Override
    public GradleInternal getParent() {
        return parent == null ? null :  parent.getMutableModel();
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
    public File getGradleUserHomeDir() {
        return startParameter.getGradleUserHomeDir();
    }

    @Override
    public BuildState getOwner() {
        return getServices().get(BuildState.class);
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
    public BuildListener getBuildListenerBroadcaster() {
        return buildListenerBroadcast.getSource();
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return (StartParameterInternal) startParameter;
    }

    @Override
    public ServiceRegistry getServices() {
        return services;
    }

    @Override
    public SettingsInternal getSettings() {
        return settings;
    }

    @Override
    public ServiceRegistryFactory getServiceRegistryFactory() {
        return services.get(ServiceRegistryFactory.class);
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
//            rootProjectActions.add(getListenerBuildOperationDecorator().decorate(registrationPoint, action));
        }
    }

    @Override
    public void allprojects(final Action<? super BuildProject> action) {
        rootProject("Gradle.allprojects", project -> project.allprojects(action));
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

    @Override
    public TaskExecutionGraphInternal getTaskGraph() {
        return getServices().get(TaskExecutionGraphInternal.class);
    }

    @Override
    public void beforeProject(Action<? super BuildProject> action) {

    }

    @Override
    public void afterProject(Action<? super BuildProject> action) {

    }

    @Override
    public void projectsLoaded(Action<? super Gradle> action) {

    }

    @Override
    public void projectsEvaluated(Action<? super Gradle> action) {

    }

    @Override
    public Gradle getGradle() {
        return this;
    }

    @Override
    public void addBuildListener(BuildListener buildListener) {

    }

    @Override
    public void addListener(Object listener) {

    }

//    private void addListener(String registrationPoint, Object listener) {
//        notifyListenerRegistration(registrationPoint, listener);
//        getListenerManager().addListener(getListenerBuildOperationDecorator().decorateUnknownListener(registrationPoint, listener));
//    }
//
//    private void notifyListenerRegistration(String registrationPoint, Object listener) {
//        if (listener instanceof InternalListener) {// || listener instanceof ProjectEvaluationListener) {
//            return;
//        }
//        getListenerManager().getBroadcaster(BuildScopeListenerRegistrationListener.class)
//                .onBuildScopeListenerRegistration(listener, registrationPoint, this);
//    }


    @Override
    public void removeListener(Object listener) {

    }

    @Override
    public void useLogger(Object logger) {
        getListenerManager().useLogger(logger);
    }

    @Override
    public Collection<IncludedBuild> getIncludedBuilds() {
        if (includedBuilds == null) {
            includedBuilds = new ArrayList<>();
        }
        return includedBuilds;
    }

    @Override
    public IncludedBuild includedBuild(String name) throws Exception {
        if (includedBuilds == null) {
            includedBuilds = new ArrayList<>();
        }
        return null;
    }

}
