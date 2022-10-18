package com.tyron.builder.gradle.api;

import org.jetbrains.annotations.Nullable;

/** A Build variant that supports versioning. */
@Deprecated
public interface VersionedVariant {

    /**
     * Returns the variant versionCode. If the value is not found, then 1 is returned as this
     * is the implicit value that the platform would use.
     *
     * If not output define its own variant override then this is used for all outputs.
     */
    int getVersionCode();

    /**
     * Return the variant versionName or null if none found.
     */
    @Nullable
    String getVersionName();

}