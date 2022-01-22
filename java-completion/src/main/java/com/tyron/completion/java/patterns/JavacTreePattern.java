package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns;
import org.jetbrains.kotlin.com.intellij.patterns.TreeElementPattern;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

public class JavacTreePattern<T extends Tree, Self extends JavacTreePattern<T, Self>> extends JavacTreeElementPattern<Tree, T, Self> {

    protected JavacTreePattern(@NonNull InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected JavacTreePattern(Class<T> aClass) {
        super(aClass);
    }

    protected TreePath getPath(ProcessingContext context, Tree tree) {
        Trees trees = (Trees) context.get("trees");
        CompilationUnitTree root = (CompilationUnitTree) context.get("root");
        return trees.getPath(root, tree);
    }

    @Nullable
    @Override
    protected Tree getParent(ProcessingContext context, @NonNull Tree tree) {
        return getPath(context, tree).getParentPath().getLeaf();
    }

    @Override
    protected Tree[] getChildren(@NonNull Tree tree) {
        return new Tree[0];
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
