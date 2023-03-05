package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.core.AbstractProductFlavor;
import com.tyron.builder.model.ApiVersion;
import com.tyron.builder.model.ProductFlavor;
import com.tyron.builder.model.SigningConfig;
import com.tyron.builder.model.VectorDrawablesOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Map;

/**
 * read-only version of the ProductFlavor wrapping another ProductFlavor.
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
public class ReadOnlyProductFlavor extends ReadOnlyBaseConfig implements ProductFlavor {

    @NonNull
    /*package*/ final ProductFlavor productFlavor;

    @NonNull
    private final ReadOnlyObjectProvider readOnlyObjectProvider;

    ReadOnlyProductFlavor(
            @NonNull ProductFlavor productFlavor,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider) {
        super(productFlavor);
        this.productFlavor = productFlavor;
        this.readOnlyObjectProvider = readOnlyObjectProvider;
    }

    @Nullable
    @Override
    public String getApplicationId() {
        return productFlavor.getApplicationId();
    }

    @Nullable
    @Override
    public Integer getVersionCode() {
        return productFlavor.getVersionCode();
    }

    @Nullable
    @Override
    public String getVersionName() {
        return productFlavor.getVersionName();
    }

    @Nullable
    @Override
    public ApiVersion getMinSdkVersion() {
        return productFlavor.getMinSdkVersion();
    }

    @Nullable
    @Override
    public ApiVersion getTargetSdkVersion() {
        return productFlavor.getTargetSdkVersion();
    }

    @Nullable
    @Override
    public Integer getMaxSdkVersion() {
        return productFlavor.getMaxSdkVersion();
    }

    @Nullable
    @Override
    public Integer getRenderscriptTargetApi() {
        return productFlavor.getRenderscriptTargetApi();
    }

    @Nullable
    @Override
    public Boolean getRenderscriptSupportModeEnabled() {
        return productFlavor.getRenderscriptSupportModeEnabled();
    }

    @Nullable
    @Override
    public Boolean getRenderscriptSupportModeBlasEnabled() {
        return productFlavor.getRenderscriptSupportModeBlasEnabled();
    }

    @Nullable
    @Override
    public Boolean getRenderscriptNdkModeEnabled() {
        return productFlavor.getRenderscriptNdkModeEnabled();
    }

    @Nullable
    @Override
    public String getTestApplicationId() {
        return productFlavor.getTestApplicationId();
    }

    @Nullable
    @Override
    public String getTestInstrumentationRunner() {
        return productFlavor.getTestInstrumentationRunner();
    }

    @NonNull
    @Override
    public Map<String, String> getTestInstrumentationRunnerArguments() {
        return ImmutableMap.copyOf(productFlavor.getTestInstrumentationRunnerArguments());
    }

    @Nullable
    @Override
    public Boolean getTestHandleProfiling() {
        return productFlavor.getTestHandleProfiling();
    }

    @Nullable
    @Override
    public Boolean getTestFunctionalTest() {
        return productFlavor.getTestFunctionalTest();
    }

    @NonNull
    @Override
    public Collection<String> getResourceConfigurations() {
        return ImmutableList.copyOf(productFlavor.getResourceConfigurations());
    }

    @Nullable
    public SigningConfig getSigningConfig() {
        if (!(productFlavor instanceof AbstractProductFlavor)) {
            return null;
        }
        return readOnlyObjectProvider.getSigningConfig(
                ((AbstractProductFlavor) productFlavor).getSigningConfig());
    }

    @NonNull
    @Override
    public VectorDrawablesOptions getVectorDrawables() {
        return new ReadOnlyVectorDrawablesOptions(productFlavor.getVectorDrawables());
    }

    @Nullable
    @Override
    public String getDimension() {
        return productFlavor.getDimension();
    }

    @Nullable
    @Deprecated
    public String getFlavorDimension() {
        return productFlavor.getDimension();
    }

    @Nullable
    @Override
    public Boolean getWearAppUnbundled() {
        return productFlavor.getWearAppUnbundled();
    }
}
