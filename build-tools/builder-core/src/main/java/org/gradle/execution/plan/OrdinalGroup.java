package org.gradle.execution.plan;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents a set of nodes reachable from a particular entry point node (a "requested task")
 */
public class OrdinalGroup extends NodeGroup {
    private final int ordinal;
    private final Set<Node> entryNodes = new LinkedHashSet<>();

    OrdinalGroup(int ordinal) {
        this.ordinal = ordinal;
    }

    @Override
    public String toString() {
        return "task group " + ordinal;
    }

    @Nullable
    @Override
    public OrdinalGroup asOrdinal() {
        return this;
    }

    @Override
    public NodeGroup withOrdinalGroup(OrdinalGroup newOrdinal) {
        return newOrdinal;
    }

    @Override
    public boolean isReachableFromEntryPoint() {
        return true;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void addEntryNode(Node node) {
        entryNodes.add(node);
    }

    public String diagnostics() {
        return "group " + ordinal + " entry nodes: " + entryNodes;
    }
}