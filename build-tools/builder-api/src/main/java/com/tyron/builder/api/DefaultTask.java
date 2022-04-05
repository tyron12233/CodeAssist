package com.tyron.builder.api;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.MutableReference;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.execution.history.InputChangesInternal;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.logging.StandardOutputCapture;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.snapshot.impl.ImplementationSnapshot;
import com.tyron.builder.api.internal.tasks.DefaultTaskInputs;
import com.tyron.builder.api.internal.tasks.DefaultTaskOutputs;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDestroyablesInternal;
import com.tyron.builder.api.internal.tasks.TaskInputsInternal;
import com.tyron.builder.api.internal.tasks.TaskLocalStateInternal;
import com.tyron.builder.api.internal.tasks.TaskMutator;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.DefaultTaskDependency;
import com.tyron.builder.api.tasks.Internal;
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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class DefaultTask extends AbstractTask {

    private final TaskStateInternal state;
    private final TaskMutator taskMutator;
    private String name;

    public String toString() {
        return taskIdentity.name;
    }

    private List<InputChangesAwareTaskAction> actions;

    private final DefaultTaskDependency dependencies;

    /**
     * "lifecycle dependencies" are dependencies declared via an explicit {@link Task#dependsOn(Object...)}
     */
    private final DefaultTaskDependency lifecycleDependencies;

    private final DefaultTaskDependency mustRunAfter;
    private final DefaultTaskDependency shouldRunAfter;
    private TaskDependency finalizedBy;

    private final TaskInputsInternal inputs;
    private final TaskOutputsInternal outputs;

    private final List<? extends ResourceLock> sharedResources = new ArrayList<>();

    private boolean enabled = true;

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

        TaskContainerInternal tasks = (TaskContainerInternal) project.getTasks();

        lifecycleDependencies = new DefaultTaskDependency(tasks);
        mustRunAfter = new DefaultTaskDependency(tasks);
        shouldRunAfter = new DefaultTaskDependency(tasks);
        finalizedBy = new DefaultTaskDependency(tasks);
        dependencies = new DefaultTaskDependency(tasks, ImmutableSet.of(lifecycleDependencies));

        state = new TaskStateInternal();
        taskMutator = new TaskMutator(this);

        PropertyWalker emptyWalker = (instance, validationContext, visitor) -> {

        };
        FileCollectionFactory factory =
                project.getServices().get(FileCollectionFactory.class);
        outputs = new DefaultTaskOutputs(this, taskMutator, emptyWalker, factory);
        inputs = new DefaultTaskInputs(this, taskMutator, emptyWalker, factory);
    }



    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultTask that = (DefaultTask) o;
        return this.taskIdentity.equals(that.taskIdentity);
    }

    @Override
    public int hashCode() {
        return taskIdentity.hashCode();
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
        return Cast.uncheckedNonnullCast(actions);
    }

    @Override
    public void setActions(List<Action<? super Task>> replacements) {
        taskMutator.mutate("Task.setActions(List<Action>)", new Runnable() {
            @Override
            public void run() {
                getTaskActions().clear();
                for (Action<? super Task> action : replacements) {
                    doLast(action);
                }
            }
        });
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
    public StandardOutputCapture getStandardOutputCapture() {
        MutableReference<PrintStream> previousOutput = MutableReference.of(null);

        return new StandardOutputCapture() {
            @Override
            public StandardOutputCapture start() {
                previousOutput.set(System.out);
                return this;
            }

            @Override
            public StandardOutputCapture stop() {
                System.setOut(previousOutput.get());
                previousOutput.set(null);
                return this;
            }
        };
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
        return doFirst("doFirst {} action", action);
    }

    @Override
    public Task doFirst(String actionName, Action<? super Task> action) {
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        taskMutator.mutate("Task.doFirst(Action)", new Runnable() {
            @Override
            public void run() {
                getTaskActions().add(0, wrap(action, actionName));
            }
        });
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

    @Override
    public List<InputChangesAwareTaskAction> getTaskActions() {
        if (actions == null) {
            actions = new ArrayList<>(3);
        }
        return actions;
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
        return inputs;
    }

    @Override
    public TaskOutputsInternal getOutputs() {
        return outputs;
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

    private InputChangesAwareTaskAction wrap(final Action<? super Task> action) {
        return wrap(action, "unnamed action");
    }

    private InputChangesAwareTaskAction wrap(final Action<? super Task> action, String actionName) {
        if (action instanceof InputChangesAwareTaskAction) {
            return (InputChangesAwareTaskAction) action;
        }
        return new TaskActionWrapper(action, actionName);
    }

    private static class TaskActionWrapper implements InputChangesAwareTaskAction {
        private final Action<? super Task> action;
        private final String maybeActionName;

        /**
         * The <i>action name</i> is used to construct a human readable name for
         * the actions to be used in progress logging. It is only used if
         * the wrapped action does not already implement {@link Describable}.
         */
        public TaskActionWrapper(Action<? super Task> action, String maybeActionName) {
            this.action = action;
            this.maybeActionName = maybeActionName;
        }

        @Override
        public void setInputChanges(InputChangesInternal inputChanges) {
        }

        @Override
        public void clearInputChanges() {
        }

        @Override
        public void execute(Task task) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(action.getClass().getClassLoader());
            try {
                action.execute(task);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
        public ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher) {
            return ImplementationSnapshot.of(getActionClassName(action), hasher.getClassLoaderHash(action.getClass().getClassLoader()));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TaskActionWrapper)) {
                return false;
            }

            TaskActionWrapper that = (TaskActionWrapper) o;
            return action.equals(that.action);
        }

        @Override
        public int hashCode() {
            return action.hashCode();
        }

        @Override
        public String getDisplayName() {
            if (action instanceof Describable) {
                return ((Describable) action).getDisplayName();
            }
            return "Execute " + maybeActionName;
        }
    }

    private static String getActionClassName(Object action) {
//        if (action instanceof ScriptOrigin) {
//            ScriptOrigin origin = (ScriptOrigin) action;
//            return origin.getOriginalClassName() + "_" + origin.getContentHash();
//        } else {
//
//        }

        return action.getClass().getName();
    }
}
