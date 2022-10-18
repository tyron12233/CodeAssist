package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.Nullable;
import com.tyron.builder.api.dsl.ApkSigningConfig;

public interface VariantDimensionBinaryCompatibilityFix
        extends VariantDimensionBinaryCompatibilityFixParent {

    @Nullable
    ApkSigningConfig _internal_getSigingConfig();

    @Nullable
    default SigningConfig getSigningConfig() {
        return (SigningConfig) _internal_getSigingConfig();
    }
}