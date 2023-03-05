package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A Simple class field with name, type and value, all as strings.
 */
public interface ClassField {
    @NotNull
    String getType();

    @NotNull
    String getName();

    @NotNull
    String getValue();

    @NotNull
    String getDocumentation();

    @NotNull
    Set<String> getAnnotations();
}