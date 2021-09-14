package com.tyron.code.action;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.Tree;
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

    private final SourcePositions mPos;
    private CompilationUnitTree mCompilationUnit;

    public FindCurrentPath(JavacTask task) {
        mPos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree compilationUnitTree, Long aLong) {
        mCompilationUnit = compilationUnitTree;
        return super.visitCompilationUnit(compilationUnitTree, aLong);
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


    @Override
    public TreePath visitNewClass(NewClassTree t, Long find) {
        TreePath smaller = super.visitNewClass(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (mPos.getStartPosition(mCompilationUnit, t) <= find && find < mPos.getEndPosition(mCompilationUnit, t)) {
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
