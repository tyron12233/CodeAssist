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
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ReferenceType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;

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
