package org.jetbrains.kotlin.com.intellij.util.containers;

/**
 * Describes the result of processing a node of a tree using Depth-first search in pre-order.
 */
public enum TreeNodeProcessingResult {
    /**
     * Continue processing children of the current node and its siblings.
     */
    CONTINUE,

    /**
     * Skip processing of children of the current node and continue with the next sibling.
     */
    SKIP_CHILDREN,

    /**
     * Skip processing of children and siblings of the current node, and continue with the next sibling of its parent node.
     */
    SKIP_TO_PARENT,

    /**
     * Stop processing.
     */
    STOP;

}