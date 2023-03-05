package com.tyron.builder.gradle.internal.api;

import static com.tyron.builder.gradle.internal.api.BaseVariantImpl.TASK_ACCESS_DEPRECATION_URL;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.VariantOutput;
import com.tyron.builder.api.component.impl.ComponentUtils;
import com.tyron.builder.api.variant.FilterConfiguration;
import com.tyron.builder.api.variant.impl.VariantOutputImpl;
import com.tyron.builder.core.ComponentType;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.gradle.api.ApkVariantOutput;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.scope.TaskContainer;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.options.BooleanOption;
import com.google.common.base.MoreObjects;
import java.io.File;
import javax.inject.Inject;
import org.gradle.api.Task;

/**
 * Implementation of variant output for apk-generating variants.
 *
 * This is a wrapper around the internal data model, in order to control what is accessible
 * through the external API.
 */
public class ApkVariantOutputImpl extends BaseVariantOutputImpl implements ApkVariantOutput {

    @NonNull private ComponentType componentType;

    @Inject
    public ApkVariantOutputImpl(
            @NonNull TaskContainer taskContainer,
            @NonNull DslServices services,
            @NonNull VariantOutputImpl variantOutput,
            @NonNull ComponentType componentType) {
        super(taskContainer, services, variantOutput);
        this.componentType = componentType;
    }

    @Nullable
    @Override
    public Task getPackageApplication() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "variant.getPackageApplicationProvider()",
                        "variantOutput.getPackageApplication()",
                        TASK_ACCESS_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT);
        return taskContainer.getPackageAndroidTask().getOrNull();
    }

    @NonNull
    @Override
    public File getOutputFile() {
        Task packageAndroidArtifact =
                taskContainer.getPackageAndroidTask().getOrNull();
        if (packageAndroidArtifact != null) {
            throw new UnsupportedOperationException();
//            return new File(
//                    packageAndroidArtifact.getOutputDirectory().get().getAsFile(),
//                    variantOutput.getOutputFileName().get());
        } else {
            return super.getOutputFile();
        }
    }

    @Nullable
    @Override
    public Task getZipAlign() {
        return getPackageApplication();
    }

    @Override
    public void setVersionCodeOverride(int versionCodeOverride) {
        // only these modules can configure their versionCode
        if (componentType.isBaseModule()) {
            variantOutput.getVersionCode().set(versionCodeOverride);
        }
    }

    @Override
    public int getVersionCodeOverride() {
        // consider throwing an exception instead, as this is not reliable.
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "VariantOutput.versionCode()",
                        "ApkVariantOutput.getVersionCodeOverride()",
                        BaseVariantImpl.USE_PROPERTIES_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.USE_PROPERTIES);

        if (!services.getProjectOptions().get(BooleanOption.ENABLE_LEGACY_API)) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            new RuntimeException(
                                    "Access to deprecated legacy com.tyron.builder.gradle.api.ApkVariantOutput.getVersionCodeOverride() requires compatibility mode for Property values in new com.tyron.builder.api.variant.VariantOutput.versionCode\n"
                                            + ComponentUtils.getENABLE_LEGACY_API()));
            // return default value during sync
            return -1;
        }

        return variantOutput.getVersionCode().getOrElse(-1);
    }

    @Override
    public void setVersionNameOverride(String versionNameOverride) {
        // only these modules can configure their versionName
        if (componentType.isBaseModule()) {
            variantOutput.getVersionName().set(versionNameOverride);
        }
    }

    @Nullable
    @Override
    public String getVersionNameOverride() {
        services.getDeprecationReporter()
                .reportDeprecatedApi(
                        "VariantOutput.versionName()",
                        "ApkVariantOutput.getVersionNameOverride()",
                        BaseVariantImpl.USE_PROPERTIES_DEPRECATION_URL,
                        DeprecationReporter.DeprecationTarget.USE_PROPERTIES);

        if (!services.getProjectOptions().get(BooleanOption.ENABLE_LEGACY_API)) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            new RuntimeException(
                                    "Access to deprecated legacy com.tyron.builder.gradle.api.ApkVariantOutput.getVersionNameOverride() requires compatibility mode for Property values in new com.tyron.builder.api.variant.VariantOutput.versionName\n"
                                            + ComponentUtils.getENABLE_LEGACY_API()));
            // return default value during sync
            return null;
        }

        return variantOutput.getVersionName().getOrNull();
    }

    @Override
    public int getVersionCode() {
        if (!services.getProjectOptions().get(BooleanOption.ENABLE_LEGACY_API)) {
            services.getIssueReporter()
                    .reportError(
                            IssueReporter.Type.GENERIC,
                            new RuntimeException(
                                    "Access to deprecated legacy com.tyron.builder.gradle.api.ApkVariantOutput.versionCode requires compatibility mode for Property values in new com.tyron.builder.api.variant.VariantOutput.versionCode\n"
                                            + ComponentUtils.getENABLE_LEGACY_API()));
            // return default value during sync
            return -1;
        }

        return variantOutput.getVersionCode().getOrElse(-1);
    }

    @Override
    public String getFilter(VariantOutput.FilterType filterType) {
        FilterConfiguration filterConfiguration =
                variantOutput.getFilter(FilterConfiguration.FilterType.valueOf(filterType.name()));
        return filterConfiguration != null ? filterConfiguration.getIdentifier() : null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("variantOutput", variantOutput).toString();
    }
}
