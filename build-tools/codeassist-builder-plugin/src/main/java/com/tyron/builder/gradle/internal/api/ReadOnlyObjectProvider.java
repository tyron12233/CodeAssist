package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.dsl.ApkSigningConfig;
import com.tyron.builder.model.BuildType;
import com.tyron.builder.model.ProductFlavor;
import com.tyron.builder.model.SigningConfig;
import com.google.common.collect.Maps;
import java.util.Map;

/**
 * Provides read-only versions of BuildType, ProductFlavor and SigningConfig instances
 * so that they can safely be exposed through the variant API.
 *
 * The class creates them on the fly so that they are only created when a
 * Gradle script/plugin queries for them, and caches them so that we reuse them as needed.
 */
public class ReadOnlyObjectProvider {

    private ReadOnlyProductFlavor readOnlyDefaultConfig;

    /**
     * Map of read-only build-types. This maps the normal build type to the read-only version.
     */
    @NonNull
    private final Map<BuildType, BuildType> readOnlyBuildTypes = Maps.newIdentityHashMap();

    /**
     * Map of read-only ProductFlavor. This maps the normal flavor to the read-only version.
     */
    @NonNull
    private final Map<ProductFlavor, ProductFlavor> readOnlyFlavors = Maps.newIdentityHashMap();

    /** Map of read-only SigningConfig. This maps the normal config to the read-only version. */
    @NonNull
    private final Map<ApkSigningConfig, SigningConfig> readOnlySigningConfig =
            Maps.newIdentityHashMap();

    /**
     * Returns an read-only version of the default config.
     * @param defaultConfig the default config.
     * @return an read-only version.
     */
    @NonNull ProductFlavor getDefaultConfig(@NonNull ProductFlavor defaultConfig) {
        if (readOnlyDefaultConfig != null) {
            if (readOnlyDefaultConfig.productFlavor != defaultConfig) {
                throw new IllegalStateException("Different DefaultConfigs passed to ApiObjectProvider");
            }
        } else {
            readOnlyDefaultConfig = new ReadOnlyProductFlavor(defaultConfig, this);
        }

        return readOnlyDefaultConfig;
    }

    /**
     * Returns an read-only version of a build type.
     * @param buildType the build type.
     * @return an read-only version.
     */
    @NonNull
    public BuildType getBuildType(@NonNull BuildType buildType) {
        BuildType readOnlyBuildType = readOnlyBuildTypes.get(buildType);
        if (readOnlyBuildType == null) {
            readOnlyBuildTypes.put(buildType,
                    readOnlyBuildType = new ReadOnlyBuildType(buildType, this));
        }

        return readOnlyBuildType;
    }

    /**
     * Retuens an read-only version of a groupable product flavor.
     * @param productFlavor the product flavor.
     * @return an read-only version.
     */
    @NonNull
    public ProductFlavor getProductFlavor(@NonNull ProductFlavor productFlavor) {
        ProductFlavor readOnlyProductFlavor = readOnlyFlavors.get(productFlavor);
        if (readOnlyProductFlavor == null) {
            readOnlyFlavors.put(productFlavor,
                    readOnlyProductFlavor = new ReadOnlyProductFlavor(
                            productFlavor, this));
        }

        return readOnlyProductFlavor;
    }

    /**
     * Returns an read-only version of a signing config.
     *
     * @param signingConfig the signing config.
     * @return an read-only version.
     */
    @Nullable
    public SigningConfig getSigningConfig(@Nullable ApkSigningConfig signingConfig) {
        if (signingConfig == null) {
            return null;
        }

        SigningConfig readOnlySigningConfig = this.readOnlySigningConfig.get(signingConfig);
        if (readOnlySigningConfig == null) {
            this.readOnlySigningConfig.put(signingConfig,
                    readOnlySigningConfig = new ReadOnlySigningConfig(signingConfig));
        }

        return readOnlySigningConfig;
    }
}
