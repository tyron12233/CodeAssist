package com.tyron.completion.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

public class FindNewTypeDeclarationAt extends TreeScanner<ClassTree, Long> {

    private final SourcePositions pos;
    private final CompilationUnitTree root;

    public FindNewTypeDeclarationAt(JavacTask task, CompilationUnitTree root) {
        this.pos = Trees.instance(task).getSourcePositions();
        this.root = root;
    }

    @Override
    public ClassTree visitNewClass(NewClassTree t, Long find) {

        if (pos == null) {
            return null;
        }

        ClassTree smaller = super.visitNewClass(t, find);
        if (smaller != null) {
            return smaller;
        }

        if (pos.getStartPosition(root, t.getClassBody()) <= find && find < pos.getEndPosition(root, t.getClassBody())) {
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
