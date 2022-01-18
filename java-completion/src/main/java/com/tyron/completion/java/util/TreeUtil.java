package com.tyron.completion.java.util;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.action.FindCurrentPath;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.LineMap;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

import java.io.IOException;

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

    public static TreePath findCurrentPath(CompileTask task, long position) {
        return new FindCurrentPath(task.task).scan(task.root(), position);
    }

    public static boolean isBlankLine(CompilationUnitTree root, long cursor) {
        LineMap lines = root.getLineMap();
        long line = lines.getLineNumber(cursor);
        long start = lines.getStartPosition(line);
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (long i = start; i < cursor; i++) {
            if (!Character.isWhitespace(contents.charAt((int) i))) {
                return false;
            }
        }
        return true;
    }

    public static TreePath findParentOfType(TreePath tree, Class<? extends Tree> type) {
        TreePath current = tree;
        while (current != null) {
            Tree leaf = current.getLeaf();
            if (type.isAssignableFrom(leaf.getClass())) {
                return current;
            }
            current = current.getParentPath();
        }
        return null;
    }
}
