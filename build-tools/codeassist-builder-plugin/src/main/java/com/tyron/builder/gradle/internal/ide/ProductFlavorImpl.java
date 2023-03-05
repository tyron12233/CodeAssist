package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.core.DefaultVectorDrawablesOptions;
import com.tyron.builder.model.ApiVersion;
import com.tyron.builder.model.ProductFlavor;
import com.tyron.builder.model.VectorDrawablesOptions;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.gradle.api.provider.Provider;

/**
 * Implementation of ProductFlavor that is serializable. Objects used in the DSL cannot be
 * serialized.
 **/
@Immutable
final class ProductFlavorImpl extends BaseConfigImpl implements ProductFlavor, Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String mDimension;
    private final ApiVersion mMinSdkVersion;
    private final ApiVersion mTargetSdkVersion;
    private final Integer mMaxSdkVersion;
    private final Integer mRenderscriptTargetApi;
    private final Boolean mRenderscriptSupportMode;
    private final Boolean mRenderscriptSupportModeBlas;
    private final Boolean mRenderscriptNdkMode;
    private final Integer mVersionCode;
    private final String mVersionName;
    private final String mApplicationId;
    private final String mTestApplicationId;
    private final String mTestInstrumentationRunner;
    private final Map<String, String> mTestInstrumentationRunnerArguments;
    private final Boolean mTestHandleProfiling;
    private final Boolean mTestFunctionalTest;
    private final Set<String> mResourceConfigurations;
    private final DefaultVectorDrawablesOptions mVectorDrawablesOptions;
    private final Boolean mWearAppUnbundled;

    public ProductFlavorImpl(
            @NonNull ProductFlavor productFlavor, @Nullable Provider<String> applicationId) {
        super(productFlavor);

        this.name = productFlavor.getName();
        this.mDimension = productFlavor.getDimension();
        this.mMinSdkVersion = ApiVersionImpl.clone(productFlavor.getMinSdkVersion());
        this.mTargetSdkVersion = ApiVersionImpl.clone(productFlavor.getTargetSdkVersion());
        this.mMaxSdkVersion = productFlavor.getMaxSdkVersion();
        this.mRenderscriptTargetApi = productFlavor.getRenderscriptTargetApi();
        this.mRenderscriptSupportMode = productFlavor.getRenderscriptSupportModeEnabled();
        this.mRenderscriptSupportModeBlas = productFlavor.getRenderscriptSupportModeBlasEnabled();
        this.mRenderscriptNdkMode = productFlavor.getRenderscriptNdkModeEnabled();

        this.mVersionCode = productFlavor.getVersionCode();
        this.mVersionName = productFlavor.getVersionName();

        // in case of merged product flavor, we can never determine the final application at this
        // time.
        this.mApplicationId =
                applicationId != null ? applicationId.get() : productFlavor.getApplicationId();

        this.mTestApplicationId = productFlavor.getTestApplicationId();
        this.mTestInstrumentationRunner = productFlavor.getTestInstrumentationRunner();
        this.mTestHandleProfiling = productFlavor.getTestHandleProfiling();
        this.mTestFunctionalTest = productFlavor.getTestFunctionalTest();

        this.mResourceConfigurations = ImmutableSet.copyOf(
                productFlavor.getResourceConfigurations());

        this.mTestInstrumentationRunnerArguments = Maps.newHashMap(
                productFlavor.getTestInstrumentationRunnerArguments());

        this.mVectorDrawablesOptions =
                com.tyron.builder.gradle.internal.dsl.VectorDrawablesOptions.copyOf(
                        productFlavor.getVectorDrawables());

        this.mWearAppUnbundled = productFlavor.getWearAppUnbundled();
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    @Nullable
    public String getApplicationId() {
        return mApplicationId;
    }

    @Override
    @Nullable
    public Integer getVersionCode() {
        return mVersionCode;
    }

    @Override
    @Nullable
    public String getVersionName() {
        return mVersionName;
    }

    @Override
    @Nullable
    public ApiVersion getMinSdkVersion() {
        return mMinSdkVersion;
    }

    @Override
    @Nullable
    public ApiVersion getTargetSdkVersion() {
        return mTargetSdkVersion;
    }

    @Override
    @Nullable
    public Integer getMaxSdkVersion() { return mMaxSdkVersion; }

    @Override
    @Nullable
    public Integer getRenderscriptTargetApi() {
        return mRenderscriptTargetApi;
    }

    @Override
    @Nullable
    public Boolean getRenderscriptSupportModeEnabled() {
        return mRenderscriptSupportMode;
    }

    @Override
    @Nullable
    public Boolean getRenderscriptSupportModeBlasEnabled() {
        return mRenderscriptSupportModeBlas;
    }

    @Override
    @Nullable
    public Boolean getRenderscriptNdkModeEnabled() {
        return mRenderscriptNdkMode;
    }

    @Nullable
    @Override
    public String getTestApplicationId() {
        return mTestApplicationId;
    }

    @Nullable
    @Override
    public String getTestInstrumentationRunner() {
        return mTestInstrumentationRunner;
    }

    @NonNull
    @Override
    public Map<String, String> getTestInstrumentationRunnerArguments() {
        return mTestInstrumentationRunnerArguments;
    }

    @Nullable
    @Override
    public Boolean getTestHandleProfiling() {
        return mTestHandleProfiling;
    }

    @Nullable
    @Override
    public Boolean getTestFunctionalTest() {
        return mTestFunctionalTest;
    }

    @NonNull
    @Override
    public Collection<String> getResourceConfigurations() {
        return mResourceConfigurations;
    }

    @NonNull
    @Override
    public VectorDrawablesOptions getVectorDrawables() {
        return mVectorDrawablesOptions;
    }

    @Nullable
    @Override
    public Boolean getWearAppUnbundled() {
        return mWearAppUnbundled;
    }

    @Nullable
    @Override
    public String getDimension() {
        return mDimension;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        ProductFlavorImpl that = (ProductFlavorImpl) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(mDimension, that.mDimension) &&
                Objects.equals(mMinSdkVersion, that.mMinSdkVersion) &&
                Objects.equals(mTargetSdkVersion, that.mTargetSdkVersion) &&
                Objects.equals(mMaxSdkVersion, that.mMaxSdkVersion) &&
                Objects.equals(mRenderscriptTargetApi, that.mRenderscriptTargetApi) &&
                Objects.equals(mRenderscriptSupportMode, that.mRenderscriptSupportMode) &&
                Objects
                        .equals(mRenderscriptSupportModeBlas, that.mRenderscriptSupportModeBlas) &&
                Objects.equals(mRenderscriptNdkMode, that.mRenderscriptNdkMode) &&
                Objects.equals(mVersionCode, that.mVersionCode) &&
                Objects.equals(mVersionName, that.mVersionName) &&
                Objects.equals(mApplicationId, that.mApplicationId) &&
                Objects.equals(mTestApplicationId, that.mTestApplicationId) &&
                Objects.equals(mTestInstrumentationRunner, that.mTestInstrumentationRunner) &&
                Objects.equals(mTestInstrumentationRunnerArguments,
                        that.mTestInstrumentationRunnerArguments) &&
                Objects.equals(mTestHandleProfiling, that.mTestHandleProfiling) &&
                Objects.equals(mTestFunctionalTest, that.mTestFunctionalTest) &&
                Objects.equals(mResourceConfigurations, that.mResourceConfigurations) &&
                Objects.equals(mVectorDrawablesOptions, that.mVectorDrawablesOptions) &&
                Objects.equals(mWearAppUnbundled, that.mWearAppUnbundled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, mDimension, mMinSdkVersion, mTargetSdkVersion,
                mMaxSdkVersion, mRenderscriptTargetApi, mRenderscriptSupportMode,
                mRenderscriptSupportModeBlas, mRenderscriptNdkMode, mVersionCode, mVersionName,
                mApplicationId, mTestApplicationId, mTestInstrumentationRunner,
                mTestInstrumentationRunnerArguments, mTestHandleProfiling, mTestFunctionalTest,
                mResourceConfigurations, mVectorDrawablesOptions, mWearAppUnbundled);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("mDimension", mDimension)
                .add("mMinSdkVersion", mMinSdkVersion)
                .add("mTargetSdkVersion", mTargetSdkVersion)
                .add("mMaxSdkVersion", mMaxSdkVersion)
                .add("mRenderscriptTargetApi", mRenderscriptTargetApi)
                .add("mRenderscriptSupportMode", mRenderscriptSupportMode)
                .add("mRenderscriptSupportModeBlas", mRenderscriptSupportModeBlas)
                .add("mRenderscriptNdkMode", mRenderscriptNdkMode)
                .add("mVersionCode", mVersionCode)
                .add("mVersionName", mVersionName)
                .add("mApplicationId", mApplicationId)
                .add("mTestApplicationId", mTestApplicationId)
                .add("mTestInstrumentationRunner", mTestInstrumentationRunner)
                .add("mTestInstrumentationRunnerArguments", mTestInstrumentationRunnerArguments)
                .add("mTestHandleProfiling", mTestHandleProfiling)
                .add("mTestFunctionalTest", mTestFunctionalTest)
                .add("mResourceConfigurations", mResourceConfigurations)
                .add("mVectorDrawablesOptions", mVectorDrawablesOptions)
                .add("mWearAppUnbundled", mWearAppUnbundled)
                .toString();
    }
}
