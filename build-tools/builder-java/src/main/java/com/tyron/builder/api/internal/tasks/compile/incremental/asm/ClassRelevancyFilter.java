package com.tyron.builder.api.internal.tasks.compile.incremental.asm;

import java.util.function.Predicate;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

class ClassRelevancyFilter implements Predicate<String> {

    private static final Set<String> PRIMITIVES = ImmutableSet.<String>builder()
            .add("void")
            .add("boolean")
            .add("byte")
            .add("char")
            .add("short")
            .add("int")
            .add("long")
            .add("float")
            .add("double")
            .build();

    private String excludedClassName;

    public ClassRelevancyFilter(String excludedClassName) {
        this.excludedClassName = excludedClassName;
    }

    @Override
    public boolean test(String className) {
        return !className.startsWith("java.")
               && !excludedClassName.equals(className)
               && !PRIMITIVES.contains(className);
    }
}
