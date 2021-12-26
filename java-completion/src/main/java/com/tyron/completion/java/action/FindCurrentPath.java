package com.tyron.completion.java.action;

import com.tyron.completion.java.FindNewTypeDeclarationAt;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

/**
 * Scanner to retrieve the current {@link TreePath} given the cursor position each visit methods
 * checks if the current tree is in between the cursor
 */
public class FindCurrentPath extends TreePathScanner<TreePath, Long> {

    private final JavacTask task;
    private final SourcePositions mPos;
    private CompilationUnitTree mCompilationUnit;

    public FindCurrentPath(JavacTask task) {
        this.task = task;
        mPos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree compilationUnitTree, Long aLong) {
        mCompilationUnit = compilationUnitTree;
        return super.visitCompilationUnit(compilationUnitTree, aLong);
    }

    @Override
    public TreePath visitMethodInvocation(MethodInvocationTree tree, Long cursor) {
        if (tree instanceof JCTree.JCMethodInvocation) {
            if (isInside(((JCTree.JCMethodInvocation) tree).meth, cursor)) {
                return getCurrentPath();
            }
        }
        return super.visitMethodInvocation(tree, cursor);
    }

    @Override
    public TreePath visitLambdaExpression(LambdaExpressionTree t, Long find) {
        TreePath smaller = super.visitLambdaExpression(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (mPos.getStartPosition(mCompilationUnit, t) <= find && find < mPos.getEndPosition(mCompilationUnit, t)) {
            return getCurrentPath();
        }

        return null;
    }

    private boolean isInside(Tree tree, long find) {
        return mPos.getStartPosition(mCompilationUnit, tree) <= find && find < mPos.getEndPosition(mCompilationUnit, tree);
    }


    @Override
    public TreePath visitNewClass(NewClassTree t, Long find) {
        TreePath smaller  = super.visitNewClass(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t.getIdentifier(), find)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath reduce(TreePath r1, TreePath r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
