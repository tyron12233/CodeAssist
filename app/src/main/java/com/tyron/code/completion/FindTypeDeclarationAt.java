package com.tyron.code.completion;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreeScanner;
import org.openjdk.source.util.Trees;

public class FindTypeDeclarationAt extends TreeScanner<ClassTree, Long> {
    private final SourcePositions pos;
    private CompilationUnitTree root;

    public FindTypeDeclarationAt(JavacTask task) {
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public ClassTree visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public ClassTree visitNewClass(NewClassTree t, Long find) {
        long start = pos.getStartPosition(root, t.getClassBody());
        long end = pos.getEndPosition(root, t.getClassBody());
        if (pos.getStartPosition(root, t.getClassBody()) <= find && find <= pos.getEndPosition(root, t.getClassBody())) {
            return t.getClassBody();
        }
        return super.visitNewClass(t, find);
    }


    @Override
    public ClassTree visitClass(ClassTree t, Long find) {
        ClassTree smaller = super.visitClass(t, find);
        if (smaller != null) {
            return smaller;
        }
        if (pos.getStartPosition(root, t) <= find && find < pos.getEndPosition(root, t)) {
            return t;
        }
        return null;
    }

    @Override
    public ClassTree reduce(ClassTree a, ClassTree b) {
        if (a != null) return a;
        return b;
    }
}