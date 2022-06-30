package com.tyron.builder.compiler.manifest.resources;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents Android quantities.
 *
 * @see <a href="https://developer.android.com/guide/topics/resources/string-resource#Plurals">Arity
 *     strings (plurals)</a>
 */
public enum Arity {
    ZERO("zero"),
    ONE("one"),
    TWO("two"),
    FEW("few"),
    MANY("many"),
    OTHER("other");

    public static final Arity[] EMPTY_ARRAY = {};

    @NotNull
    private final String name;

    Arity(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public String getName() {
        return name;
    }

    @Nullable
    public static Arity getEnum(@NotNull String name) {
        for (Arity value : values()) {
            if (value.name.equals(name)) {
                return value;
            }
        }

        return null;
    }
}
