package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import javax.lang.model.element.ExecutableElement;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JavacTreePattern<T extends Tree, Self extends JavacTreePattern<T, Self>> extends JavacTreeElementPattern<Tree, T, Self> {

    protected JavacTreePattern(@NonNull InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected JavacTreePattern(Class<T> aClass) {
        super(aClass);
    }

    protected TreePath getPath(ProcessingContext context, Tree tree) {
        Trees trees = (Trees) context.get("trees");
        if (trees == null) {
            return null;
        }
        CompilationUnitTree root = (CompilationUnitTree) context.get("root");
        if (root == null) {
            return null;
        }
        return trees.getPath(root, tree);
    }

    @Nullable
    @Override
    protected Tree getParent(ProcessingContext context, @NonNull Tree tree) {
        TreePath path = getPath(context, tree);
        if (path == null) {
            return null;
        }
        TreePath parent = path.getParentPath();
        if (parent == null) {
            return null;
        }
        return parent.getLeaf();
    }

    @Override
    protected Tree[] getChildren(@NonNull Tree tree) {
        if (tree instanceof ExpressionStatementTree) {
            return new Tree[]{((ExpressionStatementTree) tree).getExpression()};
        }
        if (tree instanceof MethodInvocationTree) {
            List<Tree> children = new ArrayList<>();
            ExpressionTree methodSelect = ((MethodInvocationTree) tree).getMethodSelect();
            while (methodSelect != null) {
                children.add(methodSelect);
                if (methodSelect instanceof MemberSelectTree) {
                    methodSelect = ((MemberSelectTree) methodSelect).getExpression();
                } else {
                    methodSelect = null;
                }
            }
            Collections.reverse(children);
            return children.toArray(new Tree[0]);
        }
        return new Tree[0];
    }

    public Self methodCallParameter(final int index, final ElementPattern<?> methodPattern) {
        final JavacTreeNamePatternCondition nameCondition = ContainerUtil.findInstance(methodPattern.getCondition().getConditions(), JavacTreeNamePatternCondition.class);

        return with(new PatternCondition<T>("methodCallParameter") {
            @Override
            public boolean accepts(@NotNull T t, ProcessingContext context) {
                Tree parent = getParent(context, t);
                if (parent instanceof MethodInvocationTree) {
                    MethodInvocationTree method = (MethodInvocationTree) parent;
                    List<? extends ExpressionTree> arguments = method.getArguments();
                    if (index >= arguments.size()) {
                        return false;
                    }
                    return checkCall(context, method, methodPattern, nameCondition);
                }
                return false;
            }
        });
    }

    private static boolean checkCall(ProcessingContext context, MethodInvocationTree tree, ElementPattern<?> methodPattern, JavacTreeNamePatternCondition nameCondition) {
        Trees trees = (Trees) context.get("trees");
        CompilationUnitTree root = (CompilationUnitTree) context.get("root");
        TreePath path = trees.getPath(root, tree);
        ExecutableElement element = (ExecutableElement) trees.getElement(path);
        if (nameCondition != null && !nameCondition.getNamePattern().accepts(element.getSimpleName().toString())) {
            return false;
        }
        return methodPattern.accepts(tree, context);
    }

    @NonNull
    public Self withName(@NonNull String name) {
        return withName(StandardPatterns.string().equalTo(name));
    }

    @NonNull
    public Self withName(@NonNull final String... names) {
        return withName(StandardPatterns.string().oneOf(names));
    }

    @NonNull
    public Self withName(@NonNull ElementPattern<String> name) {
        return with(new JavacTreeNamePatternCondition("withName", name));
    }

    public static class Capture<T extends Tree> extends JavacTreePattern<T, Capture<T>> {

        protected Capture(@NonNull InitialPatternCondition<T> condition) {
            super(condition);
        }

        protected Capture(Class<T> aClass) {
            super(aClass);
        }
    }
}
