package com.tyron.builder.gradle.api;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.api.TestedVariant;
import com.tyron.builder.gradle.internal.core.InternalBaseVariant;

/** A variant that contains all unit test code. */
@Deprecated
public interface UnitTestVariant extends BaseVariant, InternalBaseVariant {
    /**
     * Returns the build variant that is tested by this variant.
     */
    @NonNull
    TestedVariant getTestedVariant();
}