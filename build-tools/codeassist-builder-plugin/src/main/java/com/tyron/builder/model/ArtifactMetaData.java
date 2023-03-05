package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

/**
 * Meta Data for an Artifact.
 */
public interface ArtifactMetaData {

    int TYPE_ANDROID = 1;
    int TYPE_JAVA = 2;

    @NotNull
    String getName();

    boolean isTest();

    int getType();
}