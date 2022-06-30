package com.tyron.completion.java.patterns;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.patterns.ObjectPattern;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

public class JavacTreePatterns {

    public static JavacTreePattern.Capture<Tree> tree() {
        return new JavacTreePattern.Capture<>(Tree.class);
    }

    public static <T extends Tree> JavacTreePattern.Capture<T> tree(Class<T> clazz) {
        return new JavacTreePattern.Capture<>(clazz);
    }

    public static ClassTreePattern classTree() {
        return new ClassTreePattern(ClassTree.class);
    }

    public static ModifierTreePattern modifiers() {
        return new ModifierTreePattern(ModifiersTree.class);
    }

    public static ExpressionTreePattern.Capture<ExpressionTree> expression() {
        return new ExpressionTreePattern.Capture<>(ExpressionTree.class);
    }

    public static JavacFieldPattern<VariableTree> variable() {
        return new JavacFieldPattern<>(VariableTree.class);
    }

    public static JavacTreePattern.Capture<LiteralTree> literal() {
        return literal(null);
    }

    public static JavacTreePattern.Capture<LiteralTree> literal(@Nullable final ElementPattern<?> value) {
        return new JavacTreePattern.Capture<>(new InitialPatternConditionPlus<LiteralTree>(LiteralTree.class) {
            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext context) {
                return o instanceof LiteralTree && (value == null || value.accepts(((LiteralTree) o).getValue(), context));
            }
        });
    }


    public static JavacTreeElementPattern.Capture<LiteralTree> literalExpression(@Nullable final ElementPattern<?> value) {
        return new JavacTreeElementPattern.Capture<>(new InitialPatternConditionPlus<LiteralTree>(LiteralTree.class) {
            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext context) {
                return o instanceof LiteralTree && (value == null || value.accepts(((LiteralTree) o).getValue(), context));
            }
        });
    }

    public static MethodTreePattern.Capture<Tree>method() {
        return new MethodTreePattern.Capture<>(Tree.class);
    }

    public static MethodInvocationTreePattern methodInvocation() {
        return new MethodInvocationTreePattern();
    }
}
