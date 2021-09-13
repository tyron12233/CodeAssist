package com.tyron.code.action;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePathScanner;
import org.openjdk.source.util.Trees;

/**
 * Scanner to retrieve the current {@link Tree} given the cursor position each visit methods
 * checks if the current tree is in between the cursor
 */
public class FindCurrentTree extends TreePathScanner<Tree, Long> {

    private final SourcePositions mPos;
    private CompilationUnitTree mCompilationUnit;

    public FindCurrentTree(JavacTask task) {
        mPos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public Tree visitCompilationUnit(CompilationUnitTree compilationUnitTree, Long aLong) {
        mCompilationUnit = compilationUnitTree;
        return super.visitCompilationUnit(compilationUnitTree, aLong);
    }

    @Override
    public Tree visitLambdaExpression(LambdaExpressionTree t, Long find) {
        Tree smaller = super.visitLambdaExpression(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (mPos.getStartPosition(mCompilationUnit, t) <= find && find < mPos.getEndPosition(mCompilationUnit, t)) {
            return t;
        }

        return null;
    }

    @Override
    public Tree reduce(Tree r1, Tree r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
