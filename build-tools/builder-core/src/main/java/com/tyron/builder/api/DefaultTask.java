package com.tyron.builder.api;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.AbstractTask;
import com.tyron.builder.api.internal.tasks.DefaultTaskDestroyables;
import com.tyron.builder.api.internal.tasks.DefaultTaskLocalState;
import com.tyron.builder.api.plugins.Convention;
import com.tyron.builder.api.plugins.ExtensionContainer;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.execution.history.changes.InputChangesInternal;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.internal.extensibility.ExtensibleDynamicObject;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.logging.StandardOutputCapture;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.resources.ResourceLock;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;
import com.tyron.builder.api.internal.tasks.DefaultTaskInputs;
import com.tyron.builder.api.internal.tasks.DefaultTaskOutputs;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDestroyablesInternal;
import com.tyron.builder.api.internal.tasks.TaskInputsInternal;
import com.tyron.builder.api.internal.tasks.TaskMutator;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.logging.LoggingManager;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependency;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskLocalState;
import com.tyron.builder.api.internal.TaskOutputsInternal;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.slf4j.ContextAwareTaskLogger;
import com.tyron.builder.internal.logging.slf4j.DefaultContextAwareTaskLogger;

import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import groovy.lang.Closure;

public class DefaultTask extends AbstractTask {

    private static final Logger BUILD_LOGGER = Logging.getLogger(Task.class);

    private final TaskStateInternal state;
    private final TaskMutator taskMutator;
    private final TaskDestroyablesInternal taskDestroyables;
    private String name;
    private final ServiceRegistry services;
    private LoggingManagerInternal loggingManager;
    private final ContextAwareTaskLogger logger = new DefaultContextAwareTaskLogger(BUILD_LOGGER);
    private ExtensibleDynamicObject extensibleDynamicObject;
        private boolean hasCustomActions;
    private final TaskLocalState localState;
    private final Property<Duration> timeout;

    public String toString() {
        return getPath();
    }

    private List<InputChangesAwareTaskAction> actions;

    private final DefaultTaskDependency dependencies;

    /**
     * "lifecycle dependencies" are dependencies declared via an explicit {@link Task#dependsOn(Object...)}
     */
    private final DefaultTaskDependency lifecycleDependencies;

    private final DefaultTaskDependency mustRunAfter;
    private final DefaultTaskDependency shouldRunAfter;
    private final DefaultTaskDependency finalizedBy;

    private final TaskInputsInternal inputs;
    private final TaskOutputsInternal outputs;

    private final List<? extends ResourceLock> sharedResources = new ArrayList<>();

    private boolean enabled = true;
    private boolean didWork;
    private String description;

    private String group;

    private final TaskIdentity<?> taskIdentity;
    private final ProjectInternal project;
    
    public DefaultTask() {
        this(taskInfo());
    }

    protected DefaultTask(TaskInfo taskInfo) {
        super(taskInfo);
        this.taskIdentity = taskInfo.identity;
        this.name = taskIdentity.name;
        this.project = taskInfo.project;
        this.services = project.getServices();

        state = new TaskStateInternal();

        PropertyWalker emptyWalker = services.get(PropertyWalker.class);
        FileCollectionFactory factory = services.get(FileCollectionFactory.class);
        taskMutator = new TaskMutator(this);
        inputs = new DefaultTaskInputs(this, taskMutator, emptyWalker, factory);
        outputs = new DefaultTaskOutputs(this, taskMutator, emptyWalker, factory);
        taskDestroyables = new DefaultTaskDestroyables(taskMutator, factory);
        localState = new DefaultTaskLocalState(taskMutator, factory);

        TaskContainerInternal tasks = (TaskContainerInternal) project.getTasks();

        lifecycleDependencies = new DefaultTaskDependency(tasks);
        mustRunAfter = new DefaultTaskDependency(tasks);
        shouldRunAfter = new DefaultTaskDependency(tasks);
        finalizedBy = new DefaultTaskDependency(tasks);
        dependencies = new DefaultTaskDependency(tasks, ImmutableSet.of(inputs, lifecycleDependencies));

        this.timeout = project.getObjects().property(Duration.class);
    }



    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultTask that = (DefaultTask) o;
        return this.taskIdentity.equals(that.taskIdentity);
    }

    @Internal
    protected ServiceRegistry getServices() {
        return services;
    }

    @Override
    public Task doFirst(final Closure action) {
        hasCustomActions = true;
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        taskMutator.mutate("Task.doFirst(Closure)", () -> getTaskActions().add(0, convertClosureToAction(action, "doFirst {} action")));
        return this;
    }

    @Override
    public Task doLast(final Closure action) {
        hasCustomActions = true;
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        taskMutator.mutate("Task.doLast(Closure)", new Runnable() {
            @Override
            public void run() {
                getTaskActions().add(convertClosureToAction(action, "doLast {} action"));
            }
        });
        return this;
    }

    @Override
    public int hashCode() {
        return taskIdentity.hashCode();
    }

    @Internal
    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Internal
    @Override
    public List<Action<? super Task>> getActions() {
        if (actions == null) {
            actions = new ArrayList<>();
        }
        return Cast.uncheckedNonnullCast(actions);
    }

    @Override
    public void setActions(List<Action<? super Task>> replacements) {
        taskMutator.mutate("Task.setActions(List<Action>)", () -> {
            getTaskActions().clear();
            for (Action<? super Task> action : replacements) {
                doLast(action);
            }
        });
    }

    @Internal
    @Override
    public TaskDependency getTaskDependencies() {
        return dependencies;
    }

    @Override
    public Task dependsOn(final Object... paths) {
        lifecycleDependencies.add(paths);
        return this;
    }

    @Internal
    @Override
    public Set<Object> getDependsOn() {
        return lifecycleDependencies.getMutableValues();
    }

    @Override
    public void setDependsOn(Iterable<?> dependsOnTasks) {
        lifecycleDependencies.setValues(dependsOnTasks);
    }

    @Internal
    @Override
    public TaskStateInternal getState() {
        return state;
    }

    private void assertDynamicObject() {
        if (extensibleDynamicObject == null) {
            extensibleDynamicObject = new ExtensibleDynamicObject(this, taskIdentity.type, services.get(
                    InstanceGenerator.class));
        }
    }

    @Internal
    @Override
    public StandardOutputCapture getStandardOutputCapture() {
        return loggingManager();
    }

    @Override
    public TaskIdentity<?> getTaskIdentity() {
        return this.taskIdentity;
    }

    @Override
    public Path getIdentityPath() {
        return getTaskIdentity().identityPath;
    }

    @Override
    public void setDidWork(boolean didWork) {
        state.setDidWork(didWork);
    }

    @Internal
    @Override
    public boolean getDidWork() {
        return state.getDidWork();
    }

    @Internal
    @Override
    public String getPath() {
        return taskIdentity.getTaskPath();
    }

    @Override
    public Task doFirst(Action<? super Task> action) {
        return doFirst("doFirst {} action", action);
    }

    @Override
    public Task doFirst(String actionName, Action<? super Task> action) {
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        taskMutator.mutate("Task.doFirst(Action)",
                () -> getTaskActions().add(0, wrap(action, actionName)));
        return this;
    }

    @Override
    public Task doLast(Action<? super Task> action) {
        return doLast("doLast {} action", action);
    }

    @Override
    public Task doLast(String actionName, Action<? super Task> action) {
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        taskMutator.mutate("Task.doLast(Action)", () -> {
            getTaskActions().add(wrap(action, actionName));
        });
        return this;
    }

    @Internal
    @Override
    public List<InputChangesAwareTaskAction> getTaskActions() {
        if (actions == null) {
            actions = new ArrayList<>(3);
        }
        return actions;
    }

    @Override
    public boolean hasTaskActions() {
        return actions != null && !actions.isEmpty();
    }

    @Internal
    @Override
    public boolean getEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Internal
    @Override
    @Deprecated
    public Convention getConvention() {
        assertDynamicObject();
        return extensibleDynamicObject.getConvention();
    }

    @Internal
    @Override
    public ExtensionContainer getExtensions() {
        return getConvention();
    }

    @Internal
    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(String description) {
        this.description = description;
    }

    @Internal
    @Override
    public String getGroup() {
        return this.group;
    }

    @Override
    public void setGroup(String group) {
        this.group = group;
    }

    @Internal
    @Override
    public TaskDestroyablesInternal getDestroyables() {
        return taskDestroyables;
    }

    @Internal
    @Override
    public TaskInputsInternal getInputs() {
        return inputs;
    }

    @Internal
    @Override
    public TaskOutputsInternal getOutputs() {
        return outputs;
    }
    @Internal
    @Override
    public TaskLocalState getLocalState() {
        return localState;
    }

    @Override
    public boolean isHasCustomActions() {
        return hasCustomActions;
    }

    @Internal
    @Override
    public Property<Duration> getTimeout() {
        return timeout;
    }

    private static class ClosureTaskAction implements InputChangesAwareTaskAction {
        private final Closure<?> closure;
        private final String actionName;
        @Nullable
        private final UserCodeApplicationContext.Application application;

        private ClosureTaskAction(Closure<?> closure, String actionName, @Nullable UserCodeApplicationContext.Application application) {
            this.closure = closure;
            this.actionName = actionName;
            this.application = application;
        }

        @Override
        public void setInputChanges(InputChangesInternal inputChanges) {
        }

        @Override
        public void clearInputChanges() {
        }

        @Override
        public void execute(Task task) {
            if (application == null) {
                doExecute(task);
            } else {
                application.reapply(() -> doExecute(task));
            }
        }

        private void doExecute(Task task) {
            closure.setDelegate(task);
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(closure.getClass().getClassLoader());
            try {
                if (closure.getMaximumNumberOfParameters() == 0) {
                    closure.call();
                } else {
                    closure.call(task);
                }
            } catch (InvokerInvocationException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw e;
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        @Override
        public ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher) {
            return ImplementationSnapshot.of(AbstractTask.getActionClassName(closure), hasher.getClassLoaderHash(closure.getClass().getClassLoader()));
        }

        @Override
        public String getDisplayName() {
            return "Execute " + actionName;
        }
    }

    private InputChangesAwareTaskAction convertClosureToAction(Closure actionClosure, String actionName) {
        return new ClosureTaskAction(actionClosure, actionName, getServices().get(
                UserCodeApplicationContext.class).current());
    }

    @Internal
    @Override
    public File getTemporaryDir() {
        File dir = getServices().get(TemporaryFileProvider.class).newTemporaryFile(getName());
        GFileUtils.mkdirs(dir);
        return dir;
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

    @Internal
    @Override
    public TaskDependency getMustRunAfter() {
        return mustRunAfter;
    }

    @Override
    public void setFinalizedBy(final Iterable<?> finalizedByTasks) {
        taskMutator.mutate("Task.setFinalizedBy(Iterable)",
                () -> finalizedBy.setValues(finalizedByTasks));
    }

    @Override
    public Task finalizedBy(final Object... paths) {
        taskMutator.mutate("Task.finalizedBy(Object...)", () -> {
            finalizedBy.add(paths);
        });
        return this;
    }

    @Internal
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

    @Internal
    @Override
    public TaskDependency getShouldRunAfter() {
        return shouldRunAfter;
    }

    @Internal
    @Override
    public BuildProject getProject() {
        return project;
    }

    @Internal
    @Override
    public TaskDependency getLifecycleDependencies() {
        return lifecycleDependencies;
    }

    @Override
    public List<? extends ResourceLock> getSharedResources() {
        return sharedResources;
    }

    @Override
    public int compareTo(@NotNull Task otherTask) {
        int depthCompare = project.compareTo(otherTask.getProject());
        if (depthCompare == 0) {
            return getPath().compareTo(otherTask.getPath());
        } else {
            return depthCompare;
        }
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public LoggingManager getLogging() {
        return loggingManager;
    }

    private LoggingManagerInternal loggingManager() {
        if (loggingManager == null) {
            loggingManager = services.getFactory(LoggingManagerInternal.class).create();
        }
        return loggingManager;
    }
}
