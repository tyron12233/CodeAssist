package com.tyron.builder.api;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDestroyablesInternal;
import com.tyron.builder.api.internal.tasks.TaskInputsInternal;
import com.tyron.builder.api.internal.tasks.TaskLocalStateInternal;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.DefaultTaskDependency;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskDestroyables;
import com.tyron.builder.api.tasks.TaskInputs;
import com.tyron.builder.api.tasks.TaskLocalState;
import com.tyron.builder.api.tasks.TaskOutputFilePropertyBuilder;
import com.tyron.builder.api.tasks.TaskOutputs;
import com.tyron.builder.api.tasks.TaskOutputsInternal;
import com.tyron.builder.api.tasks.TaskState;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class DefaultTask extends AbstractTask {

    private String name;
    private TaskStateInternal state;

    public String toString() {
        return name;
    }

    private List<Action<? super Task>> actions;


    private final DefaultTaskDependency dependencies;

    /**
     * "lifecycle dependencies" are dependencies declared via an explicit {@link Task#dependsOn(Object...)}
     */
    private final DefaultTaskDependency lifecycleDependencies;

    private final DefaultTaskDependency mustRunAfter;
    private final DefaultTaskDependency shouldRunAfter;
    private TaskDependency finalizedBy;

    private final List<? extends ResourceLock> sharedResources = new ArrayList<>();

    private boolean enabled = true;

    private String description;

    private String group;

    private final BuildProject project;

    public DefaultTask(ProjectInternal project) {
        this.project = project;

        TaskContainerInternal tasks = project.getTasks();
        lifecycleDependencies = new DefaultTaskDependency(tasks);
        mustRunAfter = new DefaultTaskDependency(tasks);
        shouldRunAfter = new DefaultTaskDependency(tasks);
        finalizedBy = new DefaultTaskDependency(tasks);
        dependencies = new DefaultTaskDependency(tasks, ImmutableSet.of(lifecycleDependencies));

        state = new TaskStateInternal();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultTask that = (DefaultTask) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public List<Action<? super Task>> getActions() {
        if (actions == null) {
            actions = new ArrayList<>();
        }
        return actions;
    }

    @Override
    public void setActions(List<Action<? super Task>> actions) {
        getActions().clear();
        for (Action<? super Task> action : actions) {
            doLast(action);
        }
    }

    @Override
    public TaskDependency getTaskDependencies() {
        return dependencies;
    }

    @Override
    public Task dependsOn(final Object... paths) {
        lifecycleDependencies.add(paths);
        return this;
    }

    @Override
    public Set<Object> getDependsOn() {
        return lifecycleDependencies.getMutableValues();
    }

    @Override
    public void setDependsOn(Iterable<?> dependsOnTasks) {
        lifecycleDependencies.setValues(dependsOnTasks);
    }

    @Override
    public TaskStateInternal getState() {
        return state;
    }

    @Override
    public void setDidWork(boolean didWork) {

    }

    @Override
    public boolean getDidWork() {
        return false;
    }

    @Override
    public String getPath() {
        return Path.path(project.getPath() + ":" + getName()).getPath();
    }

    @Override
    public Task doFirst(Action<? super Task> action) {
        List<Action<? super Task>> actions = getActions();
        actions.add(0, action);
        return this;
    }

    @Override
    public Task doFirst(String actionName, Action<? super Task> action) {
        List<Action<? super Task>> actions = getActions();
        actions.add(0, action);
        return this;
    }

    @Override
    public Task doLast(Action<? super Task> action) {
        List<Action<? super Task>> actions = getActions();
        actions.add(action);
        return this;
    }

    @Override
    public Task doLast(String actionName, Action<? super Task> action) {
        List<Action<? super Task>> actions = getActions();
        actions.add(action);
        return this;
    }

    @Override
    public boolean getEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String getGroup() {
        return this.group;
    }

    @Override
    public void setGroup(String group) {
        this.group = group;
    }

    @Override
    public TaskInputsInternal getInputs() {
        return null;
    }

    @Override
    public TaskOutputsInternal getOutputs() {
        return new TaskOutputsInternal() {
            @Override
            public void visitRegisteredProperties(PropertyVisitor visitor) {

            }

            @Override
            public void setPreviousOutputFiles(FileCollection previousOutputFiles) {

            }

            @Override
            public Set<File> getPreviousOutputFiles() {
                return null;
            }

            @Override
            public void upToDateWhen(Predicate<? super Task> upToDateSpec) {

            }

            @Override
            public void cacheIf(Predicate<? super Task> spec) {

            }

            @Override
            public void cacheIf(String cachingEnabledReason, Predicate<? super Task> spec) {

            }

            @Override
            public boolean getHasOutput() {
                return false;
            }

            @Override
            public FileCollection getFiles() {
                return null;
            }

            @Override
            public TaskOutputFilePropertyBuilder files(Object... paths) {
                return null;
            }

            @Override
            public TaskOutputFilePropertyBuilder dirs(Object... paths) {
                return null;
            }

            @Override
            public TaskOutputFilePropertyBuilder file(Object path) {
                return null;
            }

            @Override
            public TaskOutputFilePropertyBuilder dir(Object path) {
                return null;
            }
        };
    }

    @Override
    public TaskDestroyables getDestroyables() {
        return new TaskDestroyablesInternal() {
            @Override
            public void visitRegisteredProperties(PropertyVisitor visitor) {

            }

            @Override
            public FileCollection getRegisteredFiles() {
                return null;
            }

            @Override
            public void register(Object... paths) {

            }
        };
    }

    @Override
    public TaskLocalState getLocalState() {
        return new TaskLocalStateInternal() {
            @Override
            public void visitRegisteredProperties(PropertyVisitor visitor) {

            }

            @Override
            public FileCollection getRegisteredFiles() {
                return null;
            }

            @Override
            public void register(Object... paths) {

            }
        };
    }

    @Override
    public File getTemporaryDir() {
        return null;
    }

    @Override
    public Task mustRunAfter(Object... paths) {
        this.mustRunAfter.add(paths);
        return this;
    }

    @Override
    public void setMustRunAfter(Iterable<?> mustRunAfter) {
        this.mustRunAfter.setValues(mustRunAfter);
    }

    @Override
    public TaskDependency getMustRunAfter() {
        return mustRunAfter;
    }

    @Override
    public Task finalizedBy(Object... paths) {
        return null;
    }

    @Override
    public void setFinalizedBy(Iterable<?> finalizedBy) {

    }

    @Override
    public TaskDependency getFinalizedBy() {
        return finalizedBy;
    }

    @Override
    public TaskDependency shouldRunAfter(Object... paths) {
        return this.shouldRunAfter.add(paths);
    }

    @Override
    public void setShouldRunAfter(Iterable<?> shouldRunAfter) {
        this.shouldRunAfter.setValues(shouldRunAfter);
    }

    @Override
    public TaskDependency getShouldRunAfter() {
        return shouldRunAfter;
    }

    @Override
    public BuildProject getProject() {
        return project;
    }

    @Override
    public TaskDependency getLifecycleDependencies() {
        return lifecycleDependencies;
    }

    @Override
    public List<? extends ResourceLock> getSharedResources() {
        return sharedResources;
    }

    @Override
    public int compareTo(@NotNull Task task) {
        return 0;
    }
}
