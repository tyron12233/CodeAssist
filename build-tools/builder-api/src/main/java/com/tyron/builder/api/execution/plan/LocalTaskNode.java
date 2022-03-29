package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.ImmutableActionSet;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.TaskExecutionException;
import com.tyron.builder.api.internal.tasks.properties.DefaultTaskProperties;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

public class LocalTaskNode extends TaskNode  {

    private final TaskInternal task;
    private final WorkValidationContext validationContext;
    private final ResolveMutationsNode resolveMutationsNode;
    private ImmutableActionSet<Task> postAction = ImmutableActionSet.empty();
    private Set<Node> lifecycleSuccessors;

    private boolean isolated;
    private List<? extends ResourceLock> resourceLocks;
    private TaskProperties taskProperties;

    public LocalTaskNode(TaskInternal task, NodeValidator nodeValidator, WorkValidationContext workValidationContext) {
        this.task = task;
        this.validationContext = workValidationContext;
        resolveMutationsNode = new ResolveMutationsNode(this, nodeValidator);
    }

    public WorkValidationContext getValidationContext() {
        return validationContext;
    }

    @Override
    public String toString() {
        return task.toString();
    }

    @Override
    public ImmutableActionSet<Task> getPostAction() {
        return postAction;
    }

    /**
     * Indicates that this task is isolated and so does not require the project lock in order to execute.
     */
    public void isolated() {
        isolated = true;
    }

    @Override
    public TaskInternal getTask() {
        return task;
    }

    @Override
    public boolean isPublicNode() {
        return true;
    }

    @Nullable
    @Override
    public ResourceLock getProjectToLock() {
        if (isolated) {
            return null;
        } else {
            // Running the task requires permission to execute against its containing project
            return ((ProjectInternal) task.getProject()).getOwner().getTaskExecutionLock();
        }
    }

    @Nullable
    @Override
    public ProjectInternal getOwningProject() {
        return (ProjectInternal) task.getProject();
    }

    @Override
    public List<? extends ResourceLock> getResourcesToLock() {
        if (resourceLocks == null) {
            resourceLocks = task.getSharedResources();
        }
        return resourceLocks;
    }

    @Override
    public void appendPostAction(Action<? super Task> action) {
        postAction = postAction.add(action);
    }

    @Override
    public Throwable getNodeFailure() {
        return task.getState().getFailure();
    }

    @Override
    public void rethrowNodeFailure() {
        task.getState().rethrowFailure();
    }

    @Override
    public void prepareForExecution(Action<Node> monitor) {
//        ((TaskContainerInternal) task.getProject().getTasks()).prepareForExecution(task);
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        for (Node targetNode : getDependencies(dependencyResolver)) {
            addDependencySuccessor(targetNode);
            processHardSuccessor.execute(targetNode);
        }

        lifecycleSuccessors = dependencyResolver.resolveDependenciesFor(task, task.getLifecycleDependencies());

        for (Node targetNode : getFinalizedBy(dependencyResolver)) {
            if (!(targetNode instanceof TaskNode)) {
                throw new IllegalStateException("Only tasks can be finalizers: " + targetNode);
            }
            addFinalizerNode((TaskNode) targetNode);
            processHardSuccessor.execute(targetNode);
        }
        for (Node targetNode : getMustRunAfter(dependencyResolver)) {
            addMustSuccessor((TaskNode) targetNode);
        }
        for (Node targetNode : getShouldRunAfter(dependencyResolver)) {
            addShouldSuccessor(targetNode);
        }
    }

    private void addFinalizerNode(TaskNode finalizerNode) {
        addFinalizer(finalizerNode);
        if (!finalizerNode.isInKnownState()) {
            finalizerNode.mustNotRun();
        }
    }

    private Set<Node> getDependencies(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getTaskDependencies());
    }

    private Set<Node> getFinalizedBy(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getFinalizedBy());
    }

    private Set<Node> getMustRunAfter(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getMustRunAfter());
    }

    private Set<Node> getShouldRunAfter(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getShouldRunAfter());
    }

    @Override
    public int compareTo(Node other) {
        if (getClass() != other.getClass()) {
            return getClass().getName().compareTo(other.getClass().getName());
        }
        LocalTaskNode localTask = (LocalTaskNode) other;
        return Objects.compare(task, localTask.task, Comparator.comparingInt(Object::hashCode));
    }

    @Override
    public Node getPrepareNode() {
        return resolveMutationsNode;
    }

    public void resolveMutations() {

        //noinspection ConstantConditions
        if (false) {
            // TODO: implement this
            return;
        }

        final LocalTaskNode taskNode = this;
        final TaskInternal task = getTask();
        final MutationInfo mutations = getMutationInfo();
        ProjectInternal project = (ProjectInternal) task.getProject();
        ServiceRegistry serviceRegistry = project.getServices();
        final FileCollectionFactory fileCollectionFactory = serviceRegistry.get(FileCollectionFactory.class);
        PropertyWalker propertyWalker = serviceRegistry.get(PropertyWalker.class);
        try {
            taskProperties = DefaultTaskProperties.resolve(propertyWalker, fileCollectionFactory, task);

            addOutputFilesToMutations(taskProperties.getOutputFileProperties());
            addLocalStateFilesToMutations(taskProperties.getLocalStateFiles());
            addDestroyablesToMutations(taskProperties.getDestroyableFiles());

            mutations.hasFileInputs = !taskProperties.getInputFileProperties().isEmpty();
        } catch (Exception e) {
            throw new TaskExecutionException(task, e);
        }

        if (!mutations.destroyablePaths.isEmpty()) {
            if (mutations.hasOutputs) {
                throw new IllegalStateException("Task " + taskNode + " has both outputs and destroyables defined.  A task can define either outputs or destroyables, but not both.");
            }
            if (mutations.hasFileInputs) {
                throw new IllegalStateException("Task " + taskNode + " has both inputs and destroyables defined.  A task can define either inputs or destroyables, but not both.");
            }
            if (mutations.hasLocalState) {
                throw new IllegalStateException("Task " + taskNode + " has both local state and destroyables defined.  A task can define either local state or destroyables, but not both.");
            }
        }
    }

    private void addOutputFilesToMutations(Set<OutputFilePropertySpec> outputFilePropertySpecs) {
        final MutationInfo mutations = getMutationInfo();
        outputFilePropertySpecs.forEach(spec -> {
            File outputLocation = spec.getOutputFile();
            if (outputLocation != null) {
                mutations.outputPaths.add(outputLocation.getAbsolutePath());
            }
            mutations.hasOutputs = true;
        });
    }

    private void addLocalStateFilesToMutations(FileCollection localStateFiles) {
        final MutationInfo mutations = getMutationInfo();
        localStateFiles.forEach(file -> {
            mutations.outputPaths.add(file.getAbsolutePath());
            mutations.hasLocalState = true;
        });
    }

    private void addDestroyablesToMutations(FileCollection destroyables) {
        destroyables
                .forEach(file -> getMutationInfo().destroyablePaths.add(file.getAbsolutePath()));
    }


    @Override
    public Set<Node> getLifecycleSuccessors() {
        return lifecycleSuccessors;
    }

    @Override
    public void setLifecycleSuccessors(Set<Node> lifecycleSuccessors) {
        this.lifecycleSuccessors = lifecycleSuccessors;
    }

    public TaskProperties getTaskProperties() {
        return taskProperties;
    }

    /**
     * Used to determine whether a {@link Node} consumes the <b>outcome</b> of a successor task vs. its output(s).
     *
     * @param dependency a non-successful successor node in the execution plan
     * @return true if the successor node dependency was declared with an explicit dependsOn relationship, false otherwise (implying task output -> task input relationship)
     */
    @Override
    protected boolean dependsOnOutcome(Node dependency) {
        return lifecycleSuccessors.contains(dependency);
    }
}
