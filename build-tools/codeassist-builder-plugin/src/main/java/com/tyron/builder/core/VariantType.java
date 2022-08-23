package com.tyron.builder.core;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.model.AndroidProject;
import com.tyron.builder.model.ArtifactMetaData;

import org.jetbrains.annotations.NotNull;

/**
 * Type of a variant.
 */
public enum VariantType {
    DEFAULT,
    LIBRARY,
    ANDROID_TEST(
            "androidTest",
            "AndroidTest",
            true,
            AndroidProject.ARTIFACT_ANDROID_TEST,
            ArtifactMetaData.TYPE_ANDROID),
    UNIT_TEST(
            "test",
            "UnitTest",
            false,
            AndroidProject.ARTIFACT_UNIT_TEST,
            ArtifactMetaData.TYPE_JAVA),
    ;

    public static ImmutableList<VariantType> getTestingTypes() {
        ImmutableList.Builder<VariantType> result = ImmutableList.builder();
        for (VariantType variantType : values()) {
            if (variantType.isForTesting()) {
                result.add(variantType);
            }
        }
        return result.build();
    }

    private final boolean mIsForTesting;
    private final String mPrefix;
    private final String mSuffix;
    private final boolean isSingleBuildType;
    private final String mArtifactName;
    private final int mArtifactType;

    /** App or library variant. */
    VariantType() {
        this.mIsForTesting = false;
        this.mPrefix = "";
        this.mSuffix = "";
        this.mArtifactName = AndroidProject.ARTIFACT_MAIN;
        this.mArtifactType = ArtifactMetaData.TYPE_ANDROID;
        this.isSingleBuildType = false;
    }

    /** Testing variant. */
    VariantType(
            String prefix,
            String suffix,
            boolean isSingleBuildType,
            String artifactName,
            int artifactType) {
        this.mArtifactName = artifactName;
        this.mArtifactType = artifactType;
        this.mIsForTesting = true;
        this.mPrefix = prefix;
        this.mSuffix = suffix;
        this.isSingleBuildType = isSingleBuildType;
    }

    /**
     * Returns true if the variant is automatically generated for testing purposed, false
     * otherwise.
     */
    public boolean isForTesting() {
        return mIsForTesting;
    }

    /**
     * Returns prefix used for naming source directories. This is an empty string in
     * case of non-testing variants and a camel case string otherwise, e.g. "androidTest".
     */
    @NotNull
    public String getPrefix() {
        return mPrefix;
    }

    /**
     * Returns suffix used for naming Gradle tasks. This is an empty string in
     * case of non-testing variants and a camel case string otherwise, e.g. "AndroidTest".
     */
    @NotNull
    public String getSuffix() {
        return mSuffix;
    }

    /**
     * Returns the name used in the builder model for artifacts that correspond to this variant
     * type.
     */
    @NotNull
    public String getArtifactName() {
        return mArtifactName;
    }

    /**
     * Returns the artifact type used in the builder model.
     */
    public int getArtifactType() {
        return mArtifactType;
    }

    /**
     * Whether the artifact type supports only a single build type.
     */
    public boolean isSingleBuildType() {
        return isSingleBuildType;
    }
}