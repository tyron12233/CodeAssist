package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

public class MethodInvocationTreePattern extends ExpressionTreePattern<MethodInvocationTree , MethodInvocationTreePattern> {

    public MethodInvocationTreePattern() {
        this(MethodInvocationTree.class);
    }

    protected MethodInvocationTreePattern(@NonNull InitialPatternCondition<MethodInvocationTree> condition) {
        super(condition);
    }

    protected MethodInvocationTreePattern(Class<MethodInvocationTree> aClass) {
        super(aClass);
    }

    public MethodInvocationTreePattern withName(@NonNull final String name) {
        return with(new PatternCondition<MethodInvocationTree>("withName") {
            @Override
            public boolean accepts(@NotNull MethodInvocationTree t, ProcessingContext context) {
                Trees trees = (Trees) context.get("trees");
                CompilationUnitTree root = (CompilationUnitTree) context.get("root");
                TreePath path = trees.getPath(root, t);
                ExecutableElement element = (ExecutableElement) trees.getElement(path);
                return element.getSimpleName().contentEquals(name);
            }
        });
    }

    private String getMethodName(MethodInvocationTree tree) {
        ExpressionTree methodSelect = tree.getMethodSelect();
        while (methodSelect != null) {
            if (methodSelect instanceof IdentifierTree) {
                return ((IdentifierTree) methodSelect).getName().toString();
            }

            if (methodSelect instanceof MemberSelectTree) {
                methodSelect = ((MemberSelectTree) methodSelect).getExpression();
            } else {
                methodSelect = null;
            }
        }
        return null;
    }
}
