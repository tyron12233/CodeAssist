package org.gradle.execution.plan;

import javax.annotation.Nullable;
import java.util.Collections;

/**
 * <p>Represents the reason why a node is included in the work graph. In general, nodes are included in the graph because they are either a "root node" or they are reachable from one or more "root nodes"
 * or both. There are two basic kind of "root nodes":</p>
 * <ul>
 *     <li>
 *         A "requested task", which is a task requested on the command-line. For example given `gradle a b:` then all the tasks that match `a` are treated as a requested task group (with ordinal 0)
 *         and all the tasks that match `b` are treated as another requested task group (with ordinal 1).
 *     </li>
 *     <li>A finalizer node. Each finalizer is treated as its own node group.</li>
 * </ul>
 *
 * <p>A node may be included in more than one group, for example when it is reachable from both a requested task and a finalizer.</p>
 *
 * <p>The groups that the node belongs to can affect how the node is scheduled relative to other nodes in the graph.</p>
 */
public abstract class NodeGroup {
    public static final NodeGroup DEFAULT_GROUP = new NodeGroup() {
        @Override
        public String toString() {
            return "default group";
        }

        @Nullable
        @Override
        public OrdinalGroup asOrdinal() {
            return null;
        }

        @Override
        public NodeGroup withOrdinalGroup(OrdinalGroup newOrdinal) {
            return newOrdinal;
        }

        @Override
        public boolean isReachableFromEntryPoint() {
            return false;
        }
    };

    @Nullable
    public abstract OrdinalGroup asOrdinal();

    public abstract boolean isReachableFromEntryPoint();

    @Nullable
    public FinalizerGroup asFinalizer() {
        return null;
    }

    /**
     * Returns the sequence of nodes which must complete before the given node can start. The given node must belong to this group.
     * The returned sequence is not exhaustive, i.e. it doesn't mean that the given node can start even if all nodes in it are
     * completed.
     *
     * @param node the node to check successors for
     */
    public Iterable<? extends Node> getSuccessorsFor(Node node) {
        return Collections.emptyList();
    }

    /**
     * Returns the sequence of nodes which must complete before the given node can start. The given node must belong to this group.
     * The returned sequence is not exhaustive, i.e. it doesn't mean that the given node can start even if all nodes in it are
     * completed.
     *
     * The returned sequence is a reverse of the result of {@link #getSuccessorsFor(Node)}.
     *
     * @param node the node to check successors for
     * @see #getSuccessorsFor(Node)
     */
    public Iterable<? extends Node> getSuccessorsInReverseOrderFor(Node node) {
        return Collections.emptyList();
    }

    public Node.DependenciesState checkSuccessorsCompleteFor(Node node) {
        return Node.DependenciesState.COMPLETE_AND_SUCCESSFUL;
    }

    public boolean isCanCancel() {
        return true;
    }

    public void addMember(Node node) {
    }

    public void removeMember(Node node) {
    }

    public abstract NodeGroup withOrdinalGroup(OrdinalGroup newOrdinal);
}