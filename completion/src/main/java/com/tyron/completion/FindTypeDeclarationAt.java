package com.tyron.completion;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreeScanner;
import org.openjdk.source.util.Trees;

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