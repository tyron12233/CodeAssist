package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Gradle;
import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.internal.build.BuildState;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;

public abstract class DefaultGradle implements GradleInternal {

    private final BuildState parent;
    private final ServiceRegistry services;
    private final StartParameter startParameter;
    private ProjectInternal rootProject;
    private Path identityPath;
    private ProjectInternal defaultProject;
    private boolean projectsLoaded;

    public DefaultGradle(@Nullable BuildState parent, StartParameter startParameter, ServiceRegistryFactory parentRegistry) {
        this.parent = parent;
        this.startParameter = startParameter;
        this.services = parentRegistry.createFor(this);
//        this.crossProjectConfigurator = services.get(CrossProjectConfigurator.class);
//        buildListenerBroadcast = getListenerManager().createAnonymousBroadcaster(BuildListener.class);
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

    @Override
    public String toString() {
        return rootProject == null ? "build" : ("build '" + rootProject.getName() + "'");
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
    public GradleInternal getParent() {
//        return parent == null ? null :  parent.getMutableModel();
        return ((GradleInternal) parent);
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

    @Inject
    @Override
    public abstract TaskExecutionGraphInternal getTaskGraph();

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

}
