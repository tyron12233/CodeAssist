package com.tyron.completion.java.patterns;

import com.tyron.completion.java.patterns.elements.JavacElementPattern;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.tree.Tree;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class JavacTreeNamePatternCondition<T extends Tree> extends PatternConditionPlus<Tree, String> implements JavacElementPattern {

    public JavacTreeNamePatternCondition(@NonNls String methodName, ElementPattern valuePattern) {
        super(methodName, valuePattern);
    }

    public @Nullable String getPropertyValue(@NotNull Object o) {
        if (o instanceof Tree) {
            try {
                Method method = o.getClass().getMethod("getName");
                Object invoke = method.invoke(o);
                if (invoke != null) {
                    return invoke.toString();
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                return null;
            }

            try {
                Method method = o.getClass().getMethod("getSimpleName");
                Object invoke = method.invoke(o);
                if (invoke != null) {
                    return invoke.toString();
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean processValues(Tree tree, ProcessingContext context, PairProcessor<String, ProcessingContext> processor) {
        return false;
    }

    @Override
    public boolean accepts(Element element, ProcessingContext context) {
        return getNamePattern().accepts(element.getSimpleName().toString(), context);
    }

    public ElementPattern<T> getNamePattern() {
        return getValuePattern();
    }
}
