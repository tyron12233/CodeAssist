package com.tyron.code.action;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreeScanner;
import org.openjdk.source.util.Trees;

public class FindMethodDeclarationAt extends TreeScanner<MethodTree, Long> {

    private final SourcePositions mPos;
    private CompilationUnitTree mCompilationUnit;

    public FindMethodDeclarationAt(JavacTask task) {
        mPos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public MethodTree visitCompilationUnit(CompilationUnitTree compilationUnitTree, Long aLong) {
        mCompilationUnit = compilationUnitTree;
        return super.visitCompilationUnit(compilationUnitTree, aLong);
    }

    @Override
    public MethodTree visitMethod(MethodTree methodTree, Long find) {
        MethodTree smaller = super.visitMethod(methodTree, find);
        if (smaller != null) {
            return smaller;
        }
        
        if (mPos.getStartPosition(mCompilationUnit, methodTree) <= find && find < mPos.getEndPosition(mCompilationUnit, methodTree)) {
            return methodTree;
        }

        return null;
    }

    @Override
    public MethodTree reduce(MethodTree r1, MethodTree r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
