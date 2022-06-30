package com.tyron.completion.java.util;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.action.FindCurrentPath;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public static boolean isVoid(MethodTree tree) {
        Tree returnType = tree.getReturnType();
        if (returnType.getKind() == Tree.Kind.PRIMITIVE_TYPE) {
            return ((PrimitiveTypeTree) returnType).getPrimitiveTypeKind() == TypeKind.VOID;
        }
        return false;
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

    public static List<MethodTree> getAllMethods(Trees trees, Elements elements, TreePath treePath) {
        Element element = trees.getElement(treePath);
        if (element == null) {
            return Collections.emptyList();
        }

        if (!(element instanceof TypeElement)) {
            return Collections.emptyList();
        }
        TypeElement typeElement = (TypeElement) element;
        List<MethodTree> methodTrees = new ArrayList<>();
        List<? extends Element> allMembers = elements.getAllMembers(typeElement);
        for (Element member : allMembers) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }

            Tree tree = trees.getTree(member);
            if (tree != null) {
                methodTrees.add((MethodTree) tree);
            }
        }
        return methodTrees;
    }

    public static List<MethodTree> getMethods(ClassTree t) {
        List<MethodTree> methods = new ArrayList<>();
        List<? extends Tree> members = t.getMembers();
        for (Tree member : members) {
            if (member.getKind() != Tree.Kind.METHOD) {
                continue;
            }
            methods.add((MethodTree) member);
        }
        return methods;
    }
}
