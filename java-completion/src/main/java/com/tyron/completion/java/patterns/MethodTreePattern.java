package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import com.tyron.completion.java.patterns.elements.JavacElementPattern;
import com.tyron.completion.java.patterns.elements.JavacElementPatternConditionPlus;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.code.Symbol;

public class MethodTreePattern extends JavacTreePattern<MethodTree, MethodTreePattern> implements JavacElementPattern{

    protected MethodTreePattern(@NonNull InitialPatternCondition<MethodTree> condition) {
        super(condition);
    }

    protected MethodTreePattern(Class<MethodTree> aClass) {
        super(aClass);
    }

    public MethodTreePattern definedInClass(String fqn) {
        return definedInClass(JavacTreePatterns.classTree().withQualifiedName(fqn));
    }

    public MethodTreePattern definedInClass(ElementPattern<? extends ClassTree> pattern) {
        return with(new JavacElementPatternConditionPlus<MethodTree, ClassTree>("definedInClass", pattern) {
            @Override
            public boolean processValues(Element target, ProcessingContext context,
                                         PairProcessor<Element, ProcessingContext> processor) {
                if (!processor.process(target.getEnclosingElement(), context)) {
                    return false;
                }
                for (Element enclosedElement : target.getEnclosedElements()) {
                    System.out.println(enclosedElement);
                }
                return true;
            }

            @Override
            public boolean processValues(MethodTree t, ProcessingContext context, PairProcessor<ClassTree, ProcessingContext> processor) {
                Trees trees = (Trees) context.get("trees");
                CompilationUnitTree root = (CompilationUnitTree) context.get("root");
                TreePath parentPath = trees.getPath(root, t).getParentPath();
                if (parentPath.getLeaf() instanceof ClassTree) {
                    if (!processor.process((ClassTree) parentPath.getLeaf(), context)) {
                        return false;
                    }
                }
                return false;
            }
        });
    }

    @Override
    public @NotNull MethodTreePattern with(@NotNull PatternCondition<? super MethodTree> pattern) {
        return super.with(pattern);
    }

    @Override
    public boolean accepts(@Nullable Object o, ProcessingContext context) {
        if (o instanceof Tree) {
            return super.accepts(o, context);
        }
        if (o instanceof Element) {
            Element element = (Element) o;
            for (PatternCondition<? super MethodTree> condition : getCondition().getConditions()) {
                if (condition instanceof JavacElementPattern) {
                    if (!((JavacElementPattern) condition).accepts(element, context)) {
                        return false;
                    }
                }
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean accepts(Element element, ProcessingContext context) {
        return accepts((Object) element, context);
    }
}
