package com.tyron.completion.java.action;

import com.tyron.completion.java.FindNewTypeDeclarationAt;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.TreePathScanner;
import org.openjdk.source.util.Trees;

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
        if (isInside(tree, cursor)) {
            return getCurrentPath();
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
