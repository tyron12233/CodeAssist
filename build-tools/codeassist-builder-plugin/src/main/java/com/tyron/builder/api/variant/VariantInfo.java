package com.tyron.builder.api.variant;

import com.android.annotations.NonNull;
import com.google.common.collect.ImmutableList;

/**
 * Information about the variant being built.
 *
 * <p>Only the Android Gradle Plugin should create instances of this interface.
 *
 * <p>Immutable, no access to tasks
 * @deprecated
 */
@Deprecated
public interface VariantInfo {

    /** Returns the name of the variant. This is composed of the build types and flavors */
    @NonNull
    String getFullVariantName();

    /**
     * Returns the name of the build type.
     *
     * <p>By convention, build-type settings should override flavor settings.
     */
    @NonNull
    String getBuildTypeName();

    /**
     * Returns a list of flavor names that make up this variant.
     *
     * <p>By convention settings from earlier flavors should override settings from later flavors.
     *
     * @return the ordered list of flavor names. May be empty.
     */
    @NonNull
    ImmutableList<String> getFlavorNames();

    /** Returns true if this is a test variant */
    boolean isTest();

    /** Returns true if the variant is debuggable */
    boolean isDebuggable();
}
