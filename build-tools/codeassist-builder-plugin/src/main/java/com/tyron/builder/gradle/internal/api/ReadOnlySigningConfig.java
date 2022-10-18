package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.dsl.ApkSigningConfig;
import com.tyron.builder.model.SigningConfig;
import java.io.File;

/**
 * Read-only version of the SigningConfig wrapping another SigningConfig.
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
public class ReadOnlySigningConfig implements SigningConfig {

    @NonNull private final ApkSigningConfig signingConfig;

    ReadOnlySigningConfig(@NonNull ApkSigningConfig signingConfig) {
        this.signingConfig = signingConfig;
    }

    @NonNull
    @Override
    public String getName() {
        return signingConfig.getName();
    }

    @Nullable
    @Override
    public File getStoreFile() {
        return signingConfig.getStoreFile();
    }

    @Nullable
    @Override
    public String getStorePassword() {
        return signingConfig.getStorePassword();
    }

    @Nullable
    @Override
    public String getKeyAlias() {
        return signingConfig.getKeyAlias();
    }

    @Nullable
    @Override
    public String getKeyPassword() {
        return signingConfig.getKeyPassword();
    }

    @Nullable
    @Override
    public String getStoreType() {
        return signingConfig.getStoreType();
    }

    @Override
    public boolean isV1SigningEnabled() {
        return signingConfig.isV1SigningEnabled();
    }

    @Override
    public boolean isV2SigningEnabled() {
        return signingConfig.isV2SigningEnabled();
    }

    @Override
    public boolean isSigningReady() {
        return getStoreFile() != null
                && getStorePassword() != null
                && getKeyAlias() != null
                && getKeyPassword() != null;
    }
}
