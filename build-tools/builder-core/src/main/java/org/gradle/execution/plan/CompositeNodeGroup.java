package org.gradle.execution.plan;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Represents a group of nodes that are reachable from more than one root node.
 */
public class CompositeNodeGroup extends HasFinalizers {
    private final NodeGroup ordinalGroup;
    private final Set<FinalizerGroup> finalizerGroups;
    private final boolean reachableFromEntryPoint;

    public CompositeNodeGroup(NodeGroup ordinalGroup, Set<FinalizerGroup> finalizerGroups) {
        this.ordinalGroup = ordinalGroup;
        this.finalizerGroups = finalizerGroups;
        this.reachableFromEntryPoint = reachableFromEntryPoint();
    }

    public CompositeNodeGroup(boolean reachableFromEntryPoint, NodeGroup newOrdinal, Set<FinalizerGroup> finalizerGroups) {
        this.ordinalGroup = newOrdinal;
        this.finalizerGroups = finalizerGroups;
        this.reachableFromEntryPoint = reachableFromEntryPoint;
    }

    @Override
    public String toString() {
        return "composite group, entry point: " + isReachableFromEntryPoint() + ", ordinal: " + ordinalGroup + ", groups: " + finalizerGroups;
    }

    @Nullable
    @Override
    public OrdinalGroup asOrdinal() {
        return ordinalGroup.asOrdinal();
    }

    @Override
    public NodeGroup withOrdinalGroup(OrdinalGroup newOrdinal) {
        return new CompositeNodeGroup(reachableFromEntryPoint, newOrdinal, finalizerGroups);
    }

    public NodeGroup getOrdinalGroup() {
        return ordinalGroup;
    }

    @Override
    public boolean isReachableFromEntryPoint() {
        return reachableFromEntryPoint;
    }

    private boolean reachableFromEntryPoint() {
        if (ordinalGroup.isReachableFromEntryPoint()) {
            return true;
        }
        for (FinalizerGroup group : finalizerGroups) {
            if (group.isReachableFromEntryPoint()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addMember(Node node) {
        for (FinalizerGroup group : finalizerGroups) {
            group.addMember(node);
        }
    }

    @Override
    public void removeMember(Node node) {
        for (FinalizerGroup group : finalizerGroups) {
            group.removeMember(node);
        }
    }

    @Override
    public Set<FinalizerGroup> getFinalizerGroups() {
        return finalizerGroups;
    }

    @Override
    public boolean isCanCancel() {
        return isCanCancel(finalizerGroups);
    }

    @Override
    public Node.DependenciesState checkSuccessorsCompleteFor(Node node) {
        if (ordinalGroup.isReachableFromEntryPoint()) {
            // Reachable from entry point node, can run at any time
            return Node.DependenciesState.COMPLETE_AND_SUCCESSFUL;
        }
        Node.DependenciesState state = Node.DependenciesState.COMPLETE_AND_CAN_SKIP;
        for (FinalizerGroup group : finalizerGroups) {
            Node.DependenciesState groupState = group.checkSuccessorsCompleteFor(node);
            // Can run once any of the finalizer groups is ready to run
            if (groupState == Node.DependenciesState.COMPLETE_AND_SUCCESSFUL) {
                return Node.DependenciesState.COMPLETE_AND_SUCCESSFUL;
            }
            // Wait for any groups that haven't finished
            if (groupState == Node.DependenciesState.NOT_COMPLETE) {
                state = Node.DependenciesState.NOT_COMPLETE;
            }
        }
        // No finalizer group is ready to run, and either all of them have failed or some are not yet complete
        return state;
    }

}