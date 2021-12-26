package com.tyron.completion.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

public class FindTypeDeclarationAt extends TreeScanner<ClassTree, Long> {
    private final SourcePositions pos;
    private final JavacTask task;
    private CompilationUnitTree root;
    private long cursor;

    public FindTypeDeclarationAt(JavacTask task) {
        this.task = task;
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public ClassTree visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        cursor = find;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public ClassTree visitClass(ClassTree t, Long find) {
        ClassTree smaller = super.visitClass(t, find);
        if (smaller != null) {
            return smaller;
        }
        if (pos.getStartPosition(root, t) <= find && find < pos.getEndPosition(root, t)) {
            ClassTree evenSmaller = new FindNewTypeDeclarationAt(task, root).scan(t, find);
            if (evenSmaller != null) {
                return evenSmaller;
            } else {
                return t;
            }
        }
        return null;
    }

    @Override
    public ClassTree reduce(ClassTree a, ClassTree b) {
        if (a != null) return a;
        return b;
    }
}