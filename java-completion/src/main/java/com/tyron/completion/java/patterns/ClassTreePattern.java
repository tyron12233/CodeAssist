package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.Trees;

public class ClassTreePattern extends JavacTreePattern<ClassTree, ClassTreePattern> {

    protected ClassTreePattern(@NonNull InitialPatternCondition<ClassTree> condition) {
        super(condition);
    }

    protected ClassTreePattern(Class<ClassTree> aClass) {
        super(aClass);
    }

    public ClassTreePattern inheritorOf(boolean strict, final ClassTreePattern pattern) {
        return with(new PatternCondition<ClassTree>("inheritorOf") {
            @Override
            public boolean accepts(@NotNull ClassTree t, ProcessingContext context) {
                return false;
            }
        });
    }

    private static boolean isInheritor(ClassTree classTree, ElementPattern pattern, final ProcessingContext matchingContext, boolean checkThisClass) {
        if (classTree == null) return false;
        if (checkThisClass && pattern.accepts(classTree, matchingContext)) return true;
        return false;
    }
}
