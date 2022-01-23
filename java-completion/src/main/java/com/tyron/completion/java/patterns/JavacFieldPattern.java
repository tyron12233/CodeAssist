package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import com.tyron.completion.java.patterns.elements.JavacElementPatternCondition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

public class JavacFieldPattern<T extends Tree> extends JavacTreeMemberPattern<T, JavacFieldPattern<T>> {

    protected JavacFieldPattern(@NonNull InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected JavacFieldPattern(Class<T> aClass) {
        super(aClass);
    }

    public JavacFieldPattern<T> withType(@NonNull String fqn) {
        return withType(JavacTreePatterns.classTree().withQualifiedName(fqn));
    }

    public JavacFieldPattern<T> withType(@NonNull ElementPattern<? extends ClassTree> pattern) {
        return with(new JavacElementPatternCondition<T>("withType") {
            @Override
            public boolean accepts(@NonNull Element element, ProcessingContext context) {
                if (element.getKind() == ElementKind.FIELD) {
                    VariableElement variableElement = (VariableElement) element;
                }
                return pattern.accepts(element, context);
            }

            @Override
            public boolean accepts(@NotNull T t, ProcessingContext context) {
                if (t instanceof VariableTree) {
                    Tree type = ((VariableTree) t).getType();
                    if (type == null) {
                        return false;
                    }
                    Trees trees = (Trees) context.get("trees");
                    CompilationUnitTree root = (CompilationUnitTree) context.get("root");
                    TreePath path = trees.getPath(root, type);
                    if (path == null) {
                        return false;
                    }
                    Element element = trees.getElement(path);
                    return pattern.accepts(element, context);
                }
                return false;
            }
        });
    }
}
