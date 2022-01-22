package com.tyron.completion.java.util;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.TreePathScanner;

/**
 * Visits the {@link CompilationUnitTree} and builds up the fully qualified name
 * of the given class tree.
 */
public class FindQualifiedName extends TreePathScanner<String, ClassTree> {

    private final StringBuilder mBuilder = new StringBuilder();

    @Override
    public String visitCompilationUnit(CompilationUnitTree t, ClassTree classTree) {
        mBuilder.append(t.getPackageName().toString());
        return super.visitCompilationUnit(t, classTree);
    }

    @Override
    public String visitClass(ClassTree classTree, ClassTree unused) {
        if (!mBuilder.toString().isEmpty()) {
            mBuilder.append('.');
        }
        mBuilder.append(classTree.getSimpleName().toString());

        if (!unused.equals(classTree)) {
            return super.visitClass(classTree, unused);
        }
        return mBuilder.toString();
    }
}
