package com.tyron.builder.api;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.tasks.DefaultTaskDependency;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskInputs;
import com.tyron.builder.api.tasks.TaskOutputs;
import com.tyron.builder.api.tasks.TaskState;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class DefaultTask implements Task {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultTask that = (DefaultTask) o;
        return Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description);
    }

    public String toString() {
        return description;
    }

    private List<Action<? super Task>> actions;


    private final DefaultTaskDependency dependencies;

    /**
     * "lifecycle dependencies" are dependencies declared via an explicit {@link Task#dependsOn(Object...)}
     */
    private final DefaultTaskDependency lifecycleDependencies;

    private final DefaultTaskDependency mustRunAfter;
    private final DefaultTaskDependency shouldRunAfter;


    private boolean enabled = true;

    private String description;

    private String group;

    public DefaultTask() {
        lifecycleDependencies = new DefaultTaskDependency();
        mustRunAfter = new DefaultTaskDependency();
        shouldRunAfter = new DefaultTaskDependency();
        dependencies = new DefaultTaskDependency(null, ImmutableSet.of(lifecycleDependencies));
    }

    @Override
    public String getName() {
        return null;
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
    public void onlyIf(Predicate<? super Task> onlyIfSpec) {

    }

    @Override
    public void setOnlyIf(Predicate<? super Task> onlyIfSpec) {

    }

    @Override
    public TaskState getState() {
        return null;
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
        return null;
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
    public TaskInputs getInputs() {
        return null;
    }

    @Override
    public TaskOutputs getOutputs() {
        return null;
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
        return null;
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
}
