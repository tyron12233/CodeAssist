package com.tyron.lint.checks;

import com.tyron.lint.api.Detector;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;

import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.VariableTree;

import java.util.Arrays;
import java.util.List;

public class JavaPerformanceDetector extends Detector implements Detector.JavaScanner {

    static final String ON_MEASURE = "onMeasure";                           //$NON-NLS-1$
    static final String ON_DRAW = "onDraw";                                 //$NON-NLS-1$
    static final String ON_LAYOUT = "onLayout";                             //$NON-NLS-1$
    private static final String INTEGER = "Integer";                        //$NON-NLS-1$
    private static final String BOOLEAN = "Boolean";                        //$NON-NLS-1$
    private static final String BYTE = "Byte";                              //$NON-NLS-1$
    private static final String LONG = "Long";                              //$NON-NLS-1$
    private static final String CHARACTER = "Character";                    //$NON-NLS-1$
    private static final String DOUBLE = "Double";                          //$NON-NLS-1$
    private static final String FLOAT = "Float";                            //$NON-NLS-1$
    private static final String HASH_MAP = "HashMap";                       //$NON-NLS-1$
    private static final String SPARSE_ARRAY = "SparseArray";               //$NON-NLS-1$
    private static final String CANVAS = "Canvas";                          //$NON-NLS-1$
    private static final String LAYOUT = "layout";

    @Override
    public JavaVoidVisitor getVisitor() {
        return new Visitor();
    }

    @Override
    public List<Class<? extends Tree>> getApplicableTypes() {
        return Arrays.asList(
                MethodTree.class,
                MethodInvocationTree.class
        );
    }

    private static class Visitor extends JavaVoidVisitor {

        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            return super.visitMethod(methodTree, unused);
        }

        private static boolean isOnDrawMethod(MethodTree node) {
            if (ON_DRAW.contentEquals(node.getName())) {
                List<? extends VariableTree> parameters = node.getParameters();
                if (parameters != null && parameters.size() == 1) {
                    VariableTree arg0 = parameters.get(0);

                }
            }

            return false;
        }
    }
}
