package com.tyron.builder.gradle.internal.api;

import com.android.annotations.Nullable;
import com.tyron.builder.gradle.TestedAndroidConfig;
import com.tyron.builder.gradle.api.TestVariant;
import com.tyron.builder.gradle.api.UnitTestVariant;

/** API for tested variant api object. */
@Deprecated
public interface TestedVariant {

    void setTestVariant(@Nullable TestVariant testVariant);

    /**
     * Returns the build variant that will test this build variant.
     *
     * <p>The android test variant exists only for one build type, by default "debug". This is
     * controlled by {@link TestedAndroidConfig#getTestBuildType}.
     */
    @Nullable
    TestVariant getTestVariant();


    /**
     * Returns the build variant that contains the unit tests for this variant.
     */
    @Nullable
    UnitTestVariant getUnitTestVariant();

    void setUnitTestVariant(@Nullable UnitTestVariant testVariant);
}

