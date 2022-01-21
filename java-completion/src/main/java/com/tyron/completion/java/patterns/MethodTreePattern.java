package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.util.Trees;

public class MethodTreePattern extends JavacTreePattern<MethodTree, MethodTreePattern> {

    protected MethodTreePattern(@NonNull InitialPatternCondition<MethodTree> condition) {
        super(condition);
    }

    protected MethodTreePattern(Class<MethodTree> aClass) {
        super(aClass);
    }
}
