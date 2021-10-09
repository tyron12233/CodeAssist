package com.tyron.completion;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreeScanner;
import org.openjdk.source.util.Trees;

public class FindNewTypeDeclarationAt extends TreeScanner<ClassTree, Long> {

    private final SourcePositions pos;
    private CompilationUnitTree root;

    public FindNewTypeDeclarationAt(JavacTask task) {
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public ClassTree visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public ClassTree visitNewClass(NewClassTree t, Long find) {

        ClassTree smaller = super.visitNewClass(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (pos.getStartPosition(root, t.getClassBody()) <= find && find <= pos.getEndPosition(root, t.getClassBody())) {
            return t.getClassBody();
        }
        return null;
    }



    @Override
    public ClassTree reduce(ClassTree a, ClassTree b) {
        if (a != null) return a;
        return b;
    }
}
