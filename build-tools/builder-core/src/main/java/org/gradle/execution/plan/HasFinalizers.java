package org.gradle.execution.plan;

import java.util.Set;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

abstract class HasFinalizers extends NodeGroup {
    public abstract NodeGroup getOrdinalGroup();

    public abstract Set<FinalizerGroup> getFinalizerGroups();

    protected boolean isCanCancel(Collection<FinalizerGroup> groups) {
        // A node cannot be cancelled if it belongs to a finalizer group that contains a finalized node that has started execution or that cannot be cancelled
        // So visit all the finalizer groups reachable from groups that the node belongs to and the finalized nodes of those groups
        Set<FinalizerGroup> seen = new HashSet<>();
        List<FinalizerGroup> queue = new ArrayList<>(groups);
        while (!queue.isEmpty()) {
            FinalizerGroup group = queue.remove(0);
            if (isTriggered(group)) {
                // Has started running at least one finalized node, so cannot cancel
                return false;
            }
            if (seen.add(group)) {
                for (Node node : group.getFinalizedNodes()) {
                    if (node.getGroup() instanceof HasFinalizers) {
                        queue.addAll(((HasFinalizers) node.getGroup()).getFinalizerGroups());
                    }
                }
            }
            // Else, have already traversed this group
        }
        return true;
    }

    private boolean isTriggered(FinalizerGroup group) {
        for (Node node : group.getFinalizedNodes()) {
            if (node.isExecuting() || node.isExecuted()) {
                return true;
            }
        }
        return false;
    }
}