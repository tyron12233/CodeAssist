package com.tyron.builder.gradle.options;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;

/** Class containing only the SigningConfig information which is injectable via ProjectOptions. */
@Immutable
public final class SigningOptions {

    // The name to use if creating a SigningConfig or SigningConfigData instance from this object.
    public static final String SIGNING_CONFIG_NAME = "externalOverride";

    /**
     * Reads the override signing options from the project properties.
     *
     * @param options the project options to read
     * @return The signing options overrides, or null if the options are not present or are
     *     incomplete.
     */
    @Nullable
    public static SigningOptions readSigningOptions(@NonNull ProjectOptions options) {
        String signingStoreFile = options.get(StringOption.IDE_SIGNING_STORE_FILE);
        String signingStorePassword = options.get(StringOption.IDE_SIGNING_STORE_PASSWORD);
        String signingKeyAlias = options.get(StringOption.IDE_SIGNING_KEY_ALIAS);
        String signingKeyPassword = options.get(StringOption.IDE_SIGNING_KEY_PASSWORD);

        if (signingStoreFile != null
                && signingStorePassword != null
                && signingKeyAlias != null
                && signingKeyPassword != null) {

            return new SigningOptions(
                    signingStoreFile,
                    signingStorePassword,
                    signingKeyAlias,
                    signingKeyPassword,
                    options.get(StringOption.IDE_SIGNING_STORE_TYPE),
                    options.get(OptionalBooleanOption.SIGNING_V1_ENABLED),
                    options.get(OptionalBooleanOption.SIGNING_V2_ENABLED));
        }

        return null;
    }

    @NonNull private final String storeFile;
    @NonNull private final String storePassword;
    @NonNull private final String keyAlias;
    @NonNull private final String keyPassword;
    @Nullable private final String storeType;
    @Nullable private final Boolean v1Enabled;
    @Nullable private final Boolean v2Enabled;

    public SigningOptions(
            @NonNull String storeFile,
            @NonNull String storePassword,
            @NonNull String keyAlias,
            @NonNull String keyPassword,
            @Nullable String storeType,
            @Nullable Boolean v1Enabled,
            @Nullable Boolean v2Enabled) {
        this.storeFile = storeFile;
        this.storeType = storeType;
        this.storePassword = storePassword;
        this.keyAlias = keyAlias;
        this.keyPassword = keyPassword;
        this.v1Enabled = v1Enabled;
        this.v2Enabled = v2Enabled;
    }

    @NonNull
    public String getStoreFile() {
        return storeFile;
    }

    @NonNull
    public String getStorePassword() {
        return storePassword;
    }

    @NonNull
    public String getKeyAlias() {
        return keyAlias;
    }

    @NonNull
    public String getKeyPassword() {
        return keyPassword;
    }

    @Nullable
    public String getStoreType() {
        return storeType;
    }

    @Nullable
    public Boolean getV1Enabled() {
        return v1Enabled;
    }

    @Nullable
    public Boolean getV2Enabled() {
        return v2Enabled;
    }
}
