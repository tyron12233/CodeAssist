package com.tyron.builder.gradle.api;

import com.tyron.builder.model.SigningConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** A Build variant for a generic android artifact. */
@Deprecated
public interface AndroidArtifactVariant extends VersionedVariant {

    /**
     * Returns the {@link SigningConfig} for this build variant, if one has been specified. This is
     * only returned for the base application module.
     */
    @Nullable
    SigningConfig getSigningConfig();

    /**
     * Returns true if this variant has the information it needs to create a signed APK. This can
     * only be true for the base application module.
     */
    boolean isSigningReady();

    /**
     * Returns the compatible screens for the variant.
     */
    @NotNull
    Set<String> getCompatibleScreens();
}