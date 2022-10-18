package org.gradle.execution.plan;

import java.util.Comparator;

/**
 * Sorts {@link Node}s to execute in the following order:
 * <ol>
 *    <li>{@link OrdinalNode} and {@link ResolveMutationsNode}</li>
 *    <li>{@link CreationOrderedNode} (a.k.a. transform nodes)</li>
 *    <li>{@link LocalTaskNode}</li>
 *    <li>{@link ActionNode}</li>
 *    <li>{@link TaskInAnotherBuild}</li>
 *    <li>remaining nodes are ordered by class name</li>
 * </ol>
 */
public class NodeComparator implements Comparator<Node> {

    public static final NodeComparator INSTANCE = new NodeComparator();

    private NodeComparator() {
    }

    @Override
    public int compare(Node o1, Node o2) {

        if (o1 instanceof OrdinalNode || o1 instanceof ResolveMutationsNode) {
            return -1;
        }
        if (o2 instanceof OrdinalNode || o2 instanceof ResolveMutationsNode) {
            return 1;
        }

        if (o1 instanceof CreationOrderedNode) {
            if (o2 instanceof CreationOrderedNode) {
                return ((CreationOrderedNode) o1).getOrder() - ((CreationOrderedNode) o2).getOrder();
            }
            return -1;
        }
        if (o2 instanceof CreationOrderedNode) {
            return 1;
        }

        if (o1 instanceof LocalTaskNode) {
            if (o2 instanceof LocalTaskNode) {
                return ((LocalTaskNode) o1).getTask().compareTo(
                        ((LocalTaskNode) o2).getTask()
                );
            }
            return -1;
        }
        if (o2 instanceof LocalTaskNode) {
            return 1;
        }

        if (o1 instanceof ActionNode) {
            return -1;
        }
        if (o2 instanceof ActionNode) {
            return 1;
        }

        if (o1 instanceof TaskInAnotherBuild && o2 instanceof TaskInAnotherBuild) {
            return ((TaskInAnotherBuild) o1).getTaskIdentityPath().compareTo(
                    ((TaskInAnotherBuild) o2).getTaskIdentityPath()
            );
        }
        int diff = o1.getClass().getName().compareTo(o2.getClass().getName());
        if (diff != 0) {
            return diff;
        }
        return -1;
    }
}