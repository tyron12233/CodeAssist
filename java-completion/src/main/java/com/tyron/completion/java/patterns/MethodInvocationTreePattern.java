package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

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

    @NonNull
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

    public MethodInvocationTreePattern withQualifier(final ElementPattern<? extends ExpressionTree> pattern) {
        return with(new PatternCondition<MethodInvocationTree>("withQualifier") {
            @Override
            public boolean accepts(@NotNull MethodInvocationTree methodInvocationTree,
                                   ProcessingContext processingContext) {
                ExpressionTree methodSelect = methodInvocationTree.getMethodSelect();
                if (methodSelect instanceof IdentifierTree) {
                    return false;
                } else {
                    MemberSelectTree memberSelectTree = (MemberSelectTree) methodSelect;
                    return pattern.accepts(memberSelectTree.getExpression(), processingContext);
                }
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
