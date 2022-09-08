package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.BaseConfig;
import com.tyron.builder.model.ClassField;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An implementation of BaseConfig specifically for sending as part of the Android model
 * through the Gradle tooling API.
 */
@Immutable
abstract class BaseConfigImpl implements BaseConfig, Serializable {

    @Nullable
    private final String mApplicationIdSuffix;
    @Nullable
    private final String mVersionNameSuffix;
    @NonNull
    private final Map<String, Object> mManifestPlaceholders;
    @NonNull
    private final Map<String, ClassField> mBuildConfigFields;
    @NonNull
    private final Map<String, ClassField> mResValues;
    @Nullable
    private final Boolean mMultiDexEnabled;
    @Nullable
    private final File mMultiDexKeepFile;
    @Nullable
    private final File mMultiDexKeepProguard;
    @NonNull private final List<File> mProguardFiles;
    @NonNull private final List<File> mConsumerProguardFiles;
    @NonNull private final List<File> mTestProguardFiles;

    protected BaseConfigImpl(@NonNull BaseConfig baseConfig) {
        mApplicationIdSuffix = baseConfig.getApplicationIdSuffix();
        mVersionNameSuffix = baseConfig.getVersionNameSuffix();
        mManifestPlaceholders = ImmutableMap.copyOf(baseConfig.getManifestPlaceholders());
        mBuildConfigFields = ImmutableMap.copyOf(baseConfig.getBuildConfigFields());
        mResValues = ImmutableMap.copyOf(baseConfig.getResValues());
        mMultiDexEnabled = baseConfig.getMultiDexEnabled();
        mMultiDexKeepFile = baseConfig.getMultiDexKeepFile();
        mMultiDexKeepProguard = baseConfig.getMultiDexKeepProguard();
        mProguardFiles = ImmutableList.copyOf(baseConfig.getProguardFiles());
        mConsumerProguardFiles = ImmutableList.copyOf(baseConfig.getConsumerProguardFiles());
        mTestProguardFiles = ImmutableList.copyOf(baseConfig.getTestProguardFiles());
    }

    @Nullable
    @Override
    public String getApplicationIdSuffix() {
        return mApplicationIdSuffix;
    }

    @Nullable
    @Override
    public String getVersionNameSuffix() {
        return mVersionNameSuffix;
    }

    @NonNull
    @Override
    public Map<String, ClassField> getBuildConfigFields() {
        return mBuildConfigFields;
    }

    @NonNull
    @Override
    public Map<String, ClassField> getResValues() {
        return mResValues;
    }

    @NonNull
    @Override
    public List<File> getProguardFiles() {
        return mProguardFiles;
    }

    @NonNull
    @Override
    public List<File> getConsumerProguardFiles() {
        return mConsumerProguardFiles;
    }

    @NonNull
    @Override
    public Collection<File> getTestProguardFiles() {
        return mTestProguardFiles;
    }

    @Override
    @NonNull
    public Map<String, Object> getManifestPlaceholders() {
        return mManifestPlaceholders;
    }

    @Override
    @Nullable
    public Boolean getMultiDexEnabled() {
        return mMultiDexEnabled;
    }

    @Nullable
    @Override
    public File getMultiDexKeepFile() {
        return mMultiDexKeepFile;
    }

    @Nullable
    @Override
    public File getMultiDexKeepProguard() {
        return mMultiDexKeepProguard;
    }

    @Override
    public String toString() {
        return "BaseConfigImpl{"
                + "applicationIdSuffix='"
                + mApplicationIdSuffix
                + '\''
                + ", versionNameSuffix='"
                + mVersionNameSuffix
                + '\''
                + ", mManifestPlaceholders="
                + mManifestPlaceholders
                + ", mBuildConfigFields="
                + mBuildConfigFields
                + ", mResValues="
                + mResValues
                + ", mMultiDexEnabled="
                + mMultiDexEnabled
                + ", mProguardFiles="
                + mProguardFiles
                + ", mConsumerProguardFiles="
                + mConsumerProguardFiles
                + ", mTestProguardFiles="
                + mTestProguardFiles
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BaseConfigImpl that = (BaseConfigImpl) o;
        return Objects.equals(mApplicationIdSuffix, that.mApplicationIdSuffix)
                && Objects.equals(mVersionNameSuffix, that.mVersionNameSuffix)
                && Objects.equals(mManifestPlaceholders, that.mManifestPlaceholders)
                && Objects.equals(mBuildConfigFields, that.mBuildConfigFields)
                && Objects.equals(mResValues, that.mResValues)
                && Objects.equals(mMultiDexEnabled, that.mMultiDexEnabled)
                && Objects.equals(mMultiDexKeepFile, that.mMultiDexKeepFile)
                && Objects.equals(mMultiDexKeepProguard, that.mMultiDexKeepProguard)
                && Objects.equals(mProguardFiles, that.mProguardFiles)
                && Objects.equals(mConsumerProguardFiles, that.mConsumerProguardFiles)
                && Objects.equals(mTestProguardFiles, that.mTestProguardFiles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mApplicationIdSuffix,
                mVersionNameSuffix,
                mManifestPlaceholders,
                mBuildConfigFields,
                mResValues,
                mMultiDexEnabled,
                mMultiDexKeepFile,
                mMultiDexKeepProguard,
                mProguardFiles,
                mConsumerProguardFiles,
                mTestProguardFiles);
    }
}
