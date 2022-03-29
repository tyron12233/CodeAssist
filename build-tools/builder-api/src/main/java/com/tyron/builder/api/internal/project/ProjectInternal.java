package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.project.BuildProject;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface ProjectInternal extends BuildProject {

    // These constants are defined here and not with the rest of their kind in HelpTasksPlugin because they are referenced
    // in the ‘core’ modules, which don't depend on ‘plugins’ where HelpTasksPlugin is defined.
    String HELP_TASK = "help";
    String TASKS_TASK = "tasks";
    String PROJECTS_TASK = "projects";

//    Attribute<String> STATUS_ATTRIBUTE = Attribute.of("org.gradle.status", String.class);

    @Nullable
    @Override
    ProjectInternal getParent();

    @Nullable
    ProjectInternal getParent(ProjectInternal referrer);

    @Override
    ProjectInternal getRootProject();

    ProjectInternal getRootProject(ProjectInternal referrer);

    BuildProject evaluate();

    ProjectInternal bindAllModelRules();

    @Override
    TaskContainerInternal getTasks();

//    ScriptSource getBuildScriptSource();

    @Override
    ProjectInternal project(String path) throws UnknownProjectException;

    ProjectInternal project(ProjectInternal referrer, String path) throws UnknownProjectException;

    ProjectInternal project(ProjectInternal referrer, String path, Action<? super BuildProject> configureAction);

    @Override
    @Nullable
    ProjectInternal findProject(String path);

    @Nullable
    ProjectInternal findProject(ProjectInternal referrer, String path);

    Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer);

    void subprojects(ProjectInternal referrer, Action<? super BuildProject> configureAction);

    Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer);

    void allprojects(ProjectInternal referrer, Action<? super BuildProject> configureAction);


    /**
     * Returns the {@link ProjectState} that manages the state of this instance.
     */
    ProjectStateUnk getOwner();

    ServiceRegistry getServices();

    ProjectStateInternal getState();

    GradleInternal getGradle();
}
