package com.tyron.completion.java.action;

import android.util.Pair;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

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
    public TreePath visitMemberReference(MemberReferenceTree t, Pair<Long, Long> find) {
        TreePath smaller = super.visitMemberReference(t, find);
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
