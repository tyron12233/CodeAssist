package com.tyron.completion.java;

import android.util.Pair;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import java.util.List;

public class FindTypeDeclarationAt extends TreeScanner<ClassTree, Long> {
    private final SourcePositions pos;
    private final JavacTask task;
    private CompilationUnitTree root;

    public FindTypeDeclarationAt(JavacTask task) {
        this.task = task;
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public ClassTree visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public ClassTree visitClass(ClassTree t, Long find) {
        ClassTree smaller = super.visitClass(t, find);
        if (smaller != null) {
            return smaller;
        }
        if (isInside(t, Pair.create(find, find))) {
            return t;
        }
        return null;
    }


    @Override
    public ClassTree visitErroneous(ErroneousTree tree, Long find) {
        final List<? extends Tree> errorTrees = tree.getErrorTrees();
        if (errorTrees != null) {
            for (Tree errorTree : errorTrees) {
                final ClassTree scan = scan(errorTree, find);
                if (scan != null) {
                    return scan;
                }
            }
        }
        return null;
    }

    @Override
    public ClassTree reduce(ClassTree a, ClassTree b) {
        if (a != null) return a;
        return b;
    }

    private boolean isInside(Tree tree, Pair<Long, Long> find) {
        long start = pos.getStartPosition(root, tree);
        long end = pos.getEndPosition(root, tree);
        return start <= find.first && find.second <= end;
    }
}