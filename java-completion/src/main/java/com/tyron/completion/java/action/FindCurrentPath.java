package com.tyron.completion.java.action;

import com.android.tools.r8.graph.P;
import com.tyron.completion.java.FindNewTypeDeclarationAt;

import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionStatementTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.PrimitiveTypeTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.TreePathScanner;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

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
    public TreePath visitClass(ClassTree classTree, Long aLong) {
        TreePath smaller = super.visitClass(classTree, aLong);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(classTree, aLong)) {
            return getCurrentPath();
        }
        return null;
    }

    @Override
    public TreePath visitMethod(MethodTree methodTree, Long aLong) {
        TreePath smaller = super.visitMethod(methodTree, aLong);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(methodTree, aLong) && !isInside(methodTree.getBody(), aLong)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitMethodInvocation(MethodInvocationTree tree, Long cursor) {
        TreePath smaller = super.visitMethodInvocation(tree, cursor);
        if (smaller != null) {
            return smaller;
        }
        if (tree instanceof JCTree.JCMethodInvocation) {
            if (isInside(tree, cursor)) {
                return getCurrentPath();
            }
        }
        return null;
    }

    @Override
    public TreePath visitPrimitiveType(PrimitiveTypeTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitPrimitiveType(t, find);
    }

    @Override
    public TreePath visitIdentifier(IdentifierTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public TreePath visitVariable(VariableTree t, Long find) {
        TreePath smaller = super.visitVariable(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return null;
    }

    @Override
    public TreePath visitBlock(BlockTree t, Long find) {
        TreePath smaller = super.visitBlock(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return null;
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
        long start = mPos.getStartPosition(mCompilationUnit, tree);
        long end = mPos.getEndPosition(mCompilationUnit, tree);
        return  start <= find && find < end;
    }


    @Override
    public TreePath visitNewClass(NewClassTree t, Long find) {

        if (isInside(t, find) && !isInside(t.getClassBody(), find)) {
            return getCurrentPath();
        }

        return super.visitNewClass(t, find);
    }

    @Override
    public TreePath reduce(TreePath r1, TreePath r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
