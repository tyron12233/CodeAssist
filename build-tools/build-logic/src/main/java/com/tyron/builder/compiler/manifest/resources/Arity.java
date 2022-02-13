package com.tyron.builder.compiler.manifest.resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

    @NonNull
    private final String name;

    Arity(@NonNull String name) {
        this.name = name;
    }

    @NonNull
    public String getName() {
        return name;
    }

    @Nullable
    public static Arity getEnum(@NonNull String name) {
        for (Arity value : values()) {
            if (value.name.equals(name)) {
                return value;
            }
        }

        return null;
    }
}
