package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.component.impl.ComponentUtils;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.gradle.api.AndroidArtifactVariant;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.variant.ApkVariantData;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.model.SigningConfig;
import java.util.Set;
import org.gradle.api.NamedDomainObjectContainer;

/**
 * Implementation of the {@link AndroidArtifactVariant} interface around a {@link ApkVariantData}
 * object.
 */
public abstract class AndroidArtifactVariantImpl extends BaseVariantImpl
        implements AndroidArtifactVariant {

    protected AndroidArtifactVariantImpl(
            @NonNull ComponentCreationConfig component,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider immutableObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        super(component, services, immutableObjectProvider, outputs);
    }

    @NonNull
    @Override
    protected abstract ApkVariantData getVariantData();

    @Override
    public SigningConfig getSigningConfig() {
        if (component.getOldVariantApiLegacySupport() != null) {
            return readOnlyObjectProvider.getSigningConfig(component.getOldVariantApiLegacySupport()
                    .getDslSigningConfig());
        } else {
            return null;
        }
    }

    @Override
    public boolean isSigningReady() {
        SigningConfig signingConfig = getSigningConfig();
        if (signingConfig != null) {
            return signingConfig.isSigningReady();
        } else {
            return false;
        }
    }

    private Integer _versionCode = null;
    private String _versionName = null;

    @Nullable
    @Override
    public String getVersionName() {
        if (!services.getProjectOptions().get(BooleanOption.ENABLE_LEGACY_API)) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            new RuntimeException(
                                    "Access to deprecated legacy com.tyron.builder.gradle.api.VersionedVariant.getVersionName() requires compatibility mode for Property values in new com.tyron.builder.api.variant.VariantOutput.versionName\n"
                                            + ComponentUtils.getENABLE_LEGACY_API()));
            // return default value during sync
            return null;
        }
        synchronized (this) {
            if (_versionName == null) {
                _versionName = component.getOutputs().getMainSplit().getVersionName().getOrNull();
            }
        }

        return _versionName;
    }

    @Override
    public int getVersionCode() {
        if (!services.getProjectOptions().get(BooleanOption.ENABLE_LEGACY_API)) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            new RuntimeException(
                                    "Access to deprecated legacy com.tyron.builder.gradle.api.VersionedVariant.getVersionCode() requires compatibility mode for Property values in new com.tyron.builder.api.variant.VariantOutput.versionCode\n"
                                            + ComponentUtils.getENABLE_LEGACY_API()));
            // return default value during sync
            return -1;
        }

        synchronized (this) {
            if (_versionCode == null) {
                _versionCode = component.getOutputs().getMainSplit().getVersionCode().getOrElse(-1);
            }
        }
        return _versionCode;
    }

    @NonNull
    @Override
    public Set<String> getCompatibleScreens() {
        return getVariantData().getCompatibleScreens();
    }
}
