package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.Nullable;
import java.util.Map;

/**
 * Base interface for external native build per-variant info.
 */
public interface CoreExternalNativeBuildOptions {
    @Nullable
    CoreExternalNativeNdkBuildOptions getExternalNativeNdkBuildOptions();

    @Nullable
    CoreExternalNativeCmakeOptions getExternalNativeCmakeOptions();

    Map<String, Object> getExternalNativeExperimentalProperties();
}