package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.util.Trees;

public class ModifierTreePattern extends JavacTreePattern<ModifiersTree, ModifierTreePattern> {

    protected ModifierTreePattern(@NonNull InitialPatternCondition<ModifiersTree> condition) {
        super(condition);
    }

    protected ModifierTreePattern(Class<ModifiersTree> aClass) {
        super(aClass);
    }

    public ModifierTreePattern withModifiers(final String... modifiers) {
        return with(new PatternCondition<ModifiersTree>("withModifiers") {
            @Override
            public boolean accepts(@NotNull ModifiersTree tree, ProcessingContext processingContext) {
                return ContainerUtil.and(modifiers,
                        t -> tree.getFlags().stream().anyMatch(it -> it.toString().equals(t)));
            }
        });
    }

    public static class Capture extends ModifierTreePattern {

        protected Capture(@NonNull InitialPatternCondition<ModifiersTree> condition) {
            super(condition);
        }
    }
}
