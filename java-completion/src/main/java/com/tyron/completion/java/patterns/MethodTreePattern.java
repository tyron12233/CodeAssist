package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.patterns.elements.JavacElementPattern;
import com.tyron.completion.java.patterns.elements.JavacElementPatternCondition;
import com.tyron.completion.java.patterns.elements.JavacElementPatternConditionPlus;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns;
import org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.ArrayType;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ReferenceType;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.code.Symbol;
import org.openjdk.tools.javac.code.Type;
import org.openjdk.tools.javac.code.Types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MethodTreePattern<T extends Tree, Self extends MethodTreePattern<T, Self>> extends JavacTreeMemberPattern<T, Self> implements JavacElementPattern{

    protected MethodTreePattern(@NonNull InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected MethodTreePattern(Class<T> aClass) {
        super(aClass);
    }

    public Self definedInClass(String fqn) {
        return definedInClass(JavacTreePatterns.classTree().withQualifiedName(fqn));
    }

    public Self definedInClass(ElementPattern<? extends ClassTree> pattern) {
        return with(new JavacElementPatternConditionPlus<Tree, Tree>("definedInClass", pattern) {
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
            public boolean processValues(Tree t, ProcessingContext context, PairProcessor<Tree, ProcessingContext> processor) {
                Trees trees = (Trees) context.get("trees");
                CompilationUnitTree root = (CompilationUnitTree) context.get("root");
                Elements elements = (Elements) context.get("elements");
                if (t instanceof MethodInvocationTree) {
                    MethodInvocationTree invocationTree = (MethodInvocationTree) t;
                    ExpressionTree methodSelect = invocationTree.getMethodSelect();
                    TreePath path = trees.getPath(root, methodSelect);
                    if (!processor.process(path.getLeaf(), context)) {
                        return false;
                    }

                    ExecutableElement element = (ExecutableElement) trees.getElement(path);
                    Element enclosingElement = element.getEnclosingElement();
                    List<? extends Element> allMembers =
                            elements.getAllMembers((TypeElement) enclosingElement);
                    for (Element allMember : allMembers) {
                        if (allMember.getKind() != ElementKind.METHOD) {
                            continue;
                        }
                        if (!getValuePattern().accepts(allMember.getEnclosingElement(), context)) {
                            return false;
                        }
                    }
                    return true;
                }
                return true;
            }
        });
    }

    @Override
    public @NotNull
    Self with(@NotNull PatternCondition<? super T> pattern) {
        return super.with(pattern);
    }

    @Override
    public boolean accepts(@Nullable Object o, ProcessingContext context) {
        if (o instanceof MethodTree) {
            return super.accepts(o, context);
        }
        if (o instanceof Tree) {
            T invocation = (T) o;
            for (PatternCondition<? super T> condition : getCondition().getConditions()) {
                if (!condition.accepts(invocation, context)) {
                    return false;
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

    public static class Capture<T extends Tree> extends MethodTreePattern<T, Capture<T>> {

        protected Capture(@NonNull InitialPatternCondition<T> condition) {
            super(condition);
        }

        protected Capture(Class<T> aClass) {
            super(aClass);
        }
    }
}
