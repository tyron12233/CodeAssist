package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.SigningConfig;
import com.google.common.base.MoreObjects;
import java.io.File;
import java.io.Serializable;
import java.util.Objects;

/**
 * Implementation of SigningConfig that is serializable. Objects used in the DSL cannot be
 * serialized.
 */
@Immutable
final class SigningConfigImpl implements SigningConfig, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String name;
    @Nullable
    private final File storeFile;
    @Nullable
    private final String storePassword;
    @Nullable
    private final String keyAlias;
    @Nullable
    private final String keyPassword;
    @Nullable
    private final String storeType;
    private final boolean v1SigningEnabled;
    private final boolean v2SigningEnabled;
    private final boolean signingReady;

    @NonNull
    static SigningConfig createSigningConfig(@NonNull SigningConfig signingConfig) {
        return new SigningConfigImpl(
                signingConfig.getName(),
                signingConfig.getStoreFile(),
                signingConfig.getStorePassword(),
                signingConfig.getKeyAlias(),
                signingConfig.getKeyPassword(),
                signingConfig.getStoreType(),
                signingConfig.isV1SigningEnabled(),
                signingConfig.isV2SigningEnabled(),
                signingConfig.isSigningReady());
    }

    private SigningConfigImpl(
            @NonNull  String name,
            @Nullable File storeFile,
            @Nullable String storePassword,
            @Nullable String keyAlias,
            @Nullable String keyPassword,
            @Nullable String storeType,
            boolean v1SigningEnabled,
            boolean v2SigningEnabled,
            boolean signingReady) {
        this.name = name;
        this.storeFile = storeFile;
        this.storePassword = storePassword;
        this.keyAlias = keyAlias;
        this.keyPassword = keyPassword;
        this.storeType = storeType;
        this.v1SigningEnabled = v1SigningEnabled;
        this.v2SigningEnabled = v2SigningEnabled;
        this.signingReady = signingReady;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    public File getStoreFile() {
        return storeFile;
    }

    @Nullable
    @Override
    public String getStorePassword() {
        return storePassword;
    }

    @Nullable
    @Override
    public String getKeyAlias() {
        return keyAlias;
    }

    @Nullable
    @Override
    public String getKeyPassword() {
        return keyPassword;
    }

    @Nullable
    @Override
    public String getStoreType() {
        return storeType;
    }

    @Override
    public boolean isV1SigningEnabled() {
        return v1SigningEnabled;
    }

    @Override
    public boolean isV2SigningEnabled() {
        return v2SigningEnabled;
    }

    @Override
    public boolean isSigningReady() {
        return signingReady;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SigningConfigImpl that = (SigningConfigImpl) o;
        return v1SigningEnabled == that.v1SigningEnabled &&
                v2SigningEnabled == that.v2SigningEnabled &&
                signingReady == that.signingReady &&
                Objects.equals(name, that.name) &&
                Objects.equals(storeFile, that.storeFile) &&
                Objects.equals(storePassword, that.storePassword) &&
                Objects.equals(keyAlias, that.keyAlias) &&
                Objects.equals(keyPassword, that.keyPassword) &&
                Objects.equals(storeType, that.storeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, storeFile, storePassword, keyAlias, keyPassword, storeType,
                v1SigningEnabled, v2SigningEnabled, signingReady);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("storeFile", storeFile)
                .add("storePassword", storePassword)
                .add("keyAlias", keyAlias)
                .add("keyPassword", keyPassword)
                .add("storeType", storeType)
                .add("v1SigningEnabled", v1SigningEnabled)
                .add("v2SigningEnabled", v2SigningEnabled)
                .add("signingReady", signingReady)
                .toString();
    }
}
