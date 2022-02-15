package com.tyron.completion.java.action;

import android.util.Pair;

import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.CaseTree;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ErroneousTree;
import org.openjdk.source.tree.ExpressionStatementTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.LiteralTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.PrimitiveTypeTree;
import org.openjdk.source.tree.SwitchTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.TreePathScanner;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

import java.util.List;

/**
 * Scanner to retrieve the current {@link TreePath} given the cursor position each visit methods
 * checks if the current tree is in between the cursor
 */
public class FindCurrentPath extends TreePathScanner<TreePath, Pair<Long, Long>> {

    private final JavacTask task;
    private final SourcePositions mPos;
    private CompilationUnitTree mCompilationUnit;

    public FindCurrentPath(JavacTask task) {
        this.task = task;
        mPos = Trees.instance(task).getSourcePositions();
    }

    public TreePath scan(Tree tree, long start) {
        return scan(tree, start, start);
    }

    public TreePath scan(Tree tree, long start, long end) {
        return super.scan(tree, Pair.create(start, end));
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree compilationUnitTree, Pair<Long,
            Long> find) {
        mCompilationUnit = compilationUnitTree;
        return super.visitCompilationUnit(compilationUnitTree, find);
    }

    @Override
    public TreePath visitImport(ImportTree importTree, Pair<Long, Long> longLongPair) {
        if (isInside(importTree, longLongPair)) {
            return getCurrentPath();
        }
        return super.visitImport(importTree, longLongPair);
    }

    @Override
    public TreePath visitClass(ClassTree classTree, Pair<Long, Long> aLong) {
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
    public TreePath visitMethod(MethodTree methodTree, Pair<Long, Long> aLong) {
        TreePath smaller = super.visitMethod(methodTree, aLong);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(methodTree, aLong)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitMethodInvocation(MethodInvocationTree tree, Pair<Long, Long> cursor) {
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
    public TreePath visitMemberSelect(MemberSelectTree t, Pair<Long, Long> find) {
        TreePath smaller = super.visitMemberSelect(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t, find)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitExpressionStatement(ExpressionStatementTree t,
                                             Pair<Long, Long> find) {
        TreePath smaller = super.visitExpressionStatement(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return null;
    }

    @Override
    public TreePath visitPrimitiveType(PrimitiveTypeTree t, Pair<Long, Long> find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitPrimitiveType(t, find);
    }

    @Override
    public TreePath visitIdentifier(IdentifierTree t, Pair<Long, Long> find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public TreePath visitVariable(VariableTree t, Pair<Long, Long> find) {
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
    public TreePath visitBlock(BlockTree t, Pair<Long, Long> find) {
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
    public TreePath visitLambdaExpression(LambdaExpressionTree t, Pair<Long, Long> find) {
        TreePath smaller = super.visitLambdaExpression(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t, find)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitLiteral(LiteralTree literalTree, Pair<Long, Long> aLong) {
        if (isInside(literalTree, aLong)) {
            return getCurrentPath();
        }
        return super.visitLiteral(literalTree, aLong);
    }

    @Override
    public TreePath visitNewClass(NewClassTree t, Pair<Long, Long> find) {
        TreePath smaller = super.visitNewClass(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(t, find) && !isInside(t.getClassBody(), find)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitSwitch(SwitchTree switchTree, Pair<Long, Long> longLongPair) {
        TreePath smaller = super.visitSwitch(switchTree, longLongPair);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(switchTree, longLongPair)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitCase(CaseTree caseTree, Pair<Long, Long> longLongPair) {
        TreePath smaller =  super.visitCase(caseTree, longLongPair);
        if (smaller != null) {
            return smaller;
        }

        if (isInside(caseTree, longLongPair)) {
            return getCurrentPath();
        }

        return null;
    }

    @Override
    public TreePath visitErroneous(ErroneousTree t, Pair<Long, Long> find) {
        List<? extends Tree> errorTrees = t.getErrorTrees();
        if (errorTrees == null) {
            return null;
        }
        for (Tree error : errorTrees) {
            TreePath scan = super.scan(error, find);
            if (scan != null) {
                return scan;
            }
        }
        return null;
    }

    @Override
    public TreePath reduce(TreePath r1, TreePath r2) {
        if (r1 != null) return r1;
        return r2;
    }

    private boolean isInside(Tree tree, Pair<Long, Long> find) {
        long start = mPos.getStartPosition(mCompilationUnit, tree);
        long end = mPos.getEndPosition(mCompilationUnit, tree);
        return start <= find.first && find.second <= end;
    }
}
