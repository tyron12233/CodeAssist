package com.tyron.builder.model;

/**
 * Workaround to allow kotlin users to treat name as a field on interfaces that contain getName().
 */
public interface Named {
    String getName();
}