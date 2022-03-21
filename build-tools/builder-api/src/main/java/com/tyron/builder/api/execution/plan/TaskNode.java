package com.tyron.builder.api.execution.plan;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.sun.org.slf4j.internal.LoggerFactory;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.TaskInternal;

import java.util.NavigableSet;
import java.util.Set;
import java.util.logging.Logger;

public abstract class TaskNode extends Node {

    private static final Logger LOGGER = Logger.getLogger("TaskNode");

    public static final int UNKNOWN_ORDINAL = -1;

    private final NavigableSet<Node> mustSuccessors = Sets.newTreeSet();
    private final Set<Node> mustPredecessors = Sets.newHashSet();
    private final NavigableSet<Node> shouldSuccessors = Sets.newTreeSet();
    private final NavigableSet<Node> finalizers = Sets.newTreeSet();
    private final NavigableSet<Node> finalizingSuccessors = Sets.newTreeSet();
    private int ordinal = UNKNOWN_ORDINAL;

    @Override
    public boolean doCheckDependenciesComplete() {
        if (!super.doCheckDependenciesComplete()) {
            return false;
        }
        LOGGER.info("Checking if all must successors are complete for " + this);
        for (Node dependency : mustSuccessors) {
            if (!dependency.isComplete()) {
                return false;
            }
        }

        LOGGER.info("Checking if all finalizing successors are complete for " + this);
        for (Node dependency : finalizingSuccessors) {
            if (!dependency.isComplete()) {
                return false;
            }
        }

        LOGGER.info("All task dependencies are complete for " + this);
        return true;
    }

    public Set<Node> getMustSuccessors() {
        return mustSuccessors;
    }

    public abstract Set<Node> getLifecycleSuccessors();

    public abstract void setLifecycleSuccessors(Set<Node> successors);

    @Override
    public Set<Node> getFinalizers() {
        return finalizers;
    }

    public Set<Node> getFinalizingSuccessors() {
        return finalizingSuccessors;
    }

    public Set<Node> getShouldSuccessors() {
        return shouldSuccessors;
    }

    public void addMustSuccessor(TaskNode toNode) {
        deprecateLifecycleHookReferencingNonLocalTask("mustRunAfter", toNode);
        mustSuccessors.add(toNode);
        toNode.mustPredecessors.add(this);
    }

    public void addFinalizingSuccessor(TaskNode finalized) {
        finalizingSuccessors.add(finalized);
        finalized.finalizers.add(this);
    }

    public void addFinalizer(TaskNode finalizerNode) {
        deprecateLifecycleHookReferencingNonLocalTask("finalizedBy", finalizerNode);
        finalizerNode.addFinalizingSuccessor(this);
    }

    public void addShouldSuccessor(Node toNode) {
        deprecateLifecycleHookReferencingNonLocalTask("shouldRunAfter", toNode);
        shouldSuccessors.add(toNode);
    }

    public void removeShouldSuccessor(TaskNode toNode) {
        shouldSuccessors.remove(toNode);
    }

    @Override
    public Iterable<Node> getAllSuccessors() {
        return Iterables.concat(
                shouldSuccessors,
                finalizingSuccessors,
                mustSuccessors,
                super.getAllSuccessors()
        );
    }

    @Override
    public Iterable<Node> getHardSuccessors() {
        return Iterables.concat(
                finalizingSuccessors,
                mustSuccessors,
                super.getHardSuccessors()
        );
    }

    @Override
    public Iterable<Node> getAllSuccessorsInReverseOrder() {
        return Iterables.concat(
                super.getAllSuccessorsInReverseOrder(),
                mustSuccessors.descendingSet(),
                finalizingSuccessors.descendingSet(),
                shouldSuccessors.descendingSet()
        );
    }

    @Override
    public Iterable<Node> getAllPredecessors() {
        return Iterables.concat(mustPredecessors, finalizers, super.getAllPredecessors());
    }

    @Override
    public boolean hasHardSuccessor(Node successor) {
        if (super.hasHardSuccessor(successor)) {
            return true;
        }
        if (!(successor instanceof TaskNode)) {
            return false;
        }
        return getMustSuccessors().contains(successor)
               || getFinalizingSuccessors().contains(successor);
    }

    /**
     * Attach an action to execute immediately after the <em>successful</em> completion of this task.
     *
     * <p>This is used to ensure that dependency resolution metadata for a particular artifact is calculated immediately after that artifact is produced and cached, to avoid consuming tasks having to lock the producing project in order to calculate this metadata.</p>
     *
     * <p>This action should really be modelled as a real node in the graph. This 'post action' concept is intended to be a step in this direction.</p>
     */
    public abstract void appendPostAction(Action<? super Task> action);

    public abstract Action<? super Task> getPostAction();

    public abstract TaskInternal getTask();

    private void deprecateLifecycleHookReferencingNonLocalTask(String hookName, Node taskNode) {
//        if (taskNode instanceof TaskInAnotherBuild) {
//            DeprecationLogger.deprecateAction("Using " + hookName + " to reference tasks from another build")
//                    .willBecomeAnErrorInGradle8()
//                    .withUpgradeGuideSection(6, "referencing_tasks_from_included_builds")
//                    .nagUser();
//        }
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void maybeSetOrdinal(int ordinal) {
        if (this.ordinal == UNKNOWN_ORDINAL || this.ordinal > ordinal) {
            this.ordinal = ordinal;
        }
    }
}
