package com.tyron.builder.gradle.internal;

import com.tyron.builder.api.transform.QualifiedContent;

/**
 * Definition of all internal scopes.
 *
 */
public enum InternalScope implements QualifiedContent.ScopeType {

    /**
     * Scope to package classes.dex files in the main split APK in InstantRun mode. All other
     * classes.dex will be packaged in other split APKs.
     */
    MAIN_SPLIT(0x10000),

    /**
     * Only the project's local dependencies (local jars). This is to be used by the library plugin
     * only (and only when building the AAR).
     */
    LOCAL_DEPS(0x20000),

    /** Only the project's feature or dynamic-feature modules. */
    FEATURES(0x40000),
    ;

    private final int value;

    InternalScope(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }
}