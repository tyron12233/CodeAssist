package org.gradle.execution.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

public final class NodeSets {

    public static NavigableSet<Node> newSortedNodeSet() {
        return new TreeSet<>(NodeComparator.INSTANCE);
    }

    public static List<Node> sortedListOf(Set<Node> nodes) {
        List<Node> sorted = new ArrayList<>(nodes);
        sorted.sort(NodeComparator.INSTANCE);
        return sorted;
    }

    private NodeSets() {
    }
}