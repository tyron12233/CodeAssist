package com.tyron.completion.java.util;

import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

public class TreeUtil {

    /**
     * From a chained method calls, find the first method call and return its path
     */
    public static Tree findCallerPath(TreePath invocation) {
        if (invocation.getLeaf() instanceof MethodInvocationTree) {
           return findCallerPath((MethodInvocationTree) invocation.getLeaf());
        }
        return null;
    }

    private static Tree findCallerPath(MethodInvocationTree invocation) {
        ExpressionTree methodSelect =
                invocation.getMethodSelect();
        if (methodSelect == null) {
            return invocation;
        }

        if (methodSelect instanceof MemberSelectTree) {
            return findCallerPath((MemberSelectTree) methodSelect);
        }

        if (methodSelect instanceof IdentifierTree) {
            return invocation;
        }
        return null;
    }

    private static Tree findCallerPath(MemberSelectTree methodSelect) {
        ExpressionTree expressionTree = methodSelect.getExpression();
        if (expressionTree == null) {
            return methodSelect;
        }
        if (expressionTree instanceof MemberSelectTree) {
            return findCallerPath((MemberSelectTree) expressionTree);
        }
        if (expressionTree instanceof MethodInvocationTree) {
            return findCallerPath((MethodInvocationTree) expressionTree);
        }
        return null;
    }
}
