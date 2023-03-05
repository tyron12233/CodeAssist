package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.core.AbstractBuildType;
import com.tyron.builder.model.BuildType;
import com.tyron.builder.model.SigningConfig;

/**
 * Read-only version of the BuildType wrapping another BuildType.
 *
 * In the variant API, it is important that the objects returned by the variants
 * are read-only.
 *
 * However, even though the API is defined to use the base interfaces as return
 * type (which all contain only getters), the dynamics of Groovy makes it easy to
 * actually use the setters of the implementation classes.
 *
 * This wrapper ensures that the returned instance is actually just a strict implementation
 * of the base interface and is read-only.
 */
public class ReadOnlyBuildType extends ReadOnlyBaseConfig implements BuildType {

    @NonNull
    private final BuildType buildType;

    @NonNull
    private final ReadOnlyObjectProvider readOnlyObjectProvider;

    public ReadOnlyBuildType(
            @NonNull BuildType buildType,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider) {
        super(buildType);
        this.buildType = buildType;
        this.readOnlyObjectProvider = readOnlyObjectProvider;
    }

    @Override
    public boolean isDebuggable() {
        return buildType.isDebuggable();
    }

    @Override
    public boolean isTestCoverageEnabled() {
        return buildType.isTestCoverageEnabled();
    }

    @Override
    public boolean isJniDebuggable() {
        return buildType.isJniDebuggable();
    }

    @Override
    public boolean isPseudoLocalesEnabled() {
        return buildType.isPseudoLocalesEnabled();
    }

    @Override
    public boolean isRenderscriptDebuggable() {
        return buildType.isRenderscriptDebuggable();
    }

    @Override
    public int getRenderscriptOptimLevel() {
        return buildType.getRenderscriptOptimLevel();
    }

    @Nullable
    @Override
    public String getVersionNameSuffix() {
        return buildType.getVersionNameSuffix();
    }

    @Override
    public boolean isMinifyEnabled() {
        return buildType.isMinifyEnabled();
    }

    @Override
    public boolean isZipAlignEnabled() {
        return buildType.isZipAlignEnabled();
    }

    @Override
    public boolean isEmbedMicroApp() {
        return buildType.isEmbedMicroApp();
    }

    @Nullable
    public SigningConfig getSigningConfig() {
        if (!(buildType instanceof AbstractBuildType)) {
            return null;
        }
        return readOnlyObjectProvider.getSigningConfig(
                ((AbstractBuildType) buildType).getSigningConfig());
    }
}
