package com.tyron.completion.java.patterns;

import org.openjdk.source.tree.ModifiersTree;
import org.openjdk.source.tree.Tree;

public class JavacTreePatterns {

    public static JavacTreePattern.Capture<Tree> tree() {
        return new JavacTreePattern.Capture<>(Tree.class);
    }

    public static <T extends Tree> JavacTreePattern.Capture<T> tree(Class<T> clazz) {
        return new JavacTreePattern.Capture<>(clazz);
    }

    public static ModifierTreePattern modifiers() {
        return new ModifierTreePattern(ModifiersTree.class);
    }
}
