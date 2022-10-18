package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.api.ApplicationVariant;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.api.TestVariant;
import com.tyron.builder.gradle.api.UnitTestVariant;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.variant.ApplicationVariantData;
import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectContainer;

/**
 * implementation of the {@link ApplicationVariant} interface around an
 * {@link ApplicationVariantData} object.
 *
 * This is a wrapper around the internal data model, in order to control what is accessible
 * through the external API.
 */
public class ApplicationVariantImpl extends ApkVariantImpl implements ApplicationVariant {

    @NonNull
    private final ApplicationVariantData variantData;

    @Nullable
    private TestVariant testVariant = null;

    @Nullable
    private UnitTestVariant unitTestVariant = null;

    @Inject
    public ApplicationVariantImpl(
            @NonNull ApplicationVariantData variantData,
            @NonNull ComponentCreationConfig component,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        super(component, services, readOnlyObjectProvider, outputs);
        this.variantData = variantData;
    }

    @Override
    @NonNull
    public ApplicationVariantData getVariantData() {
        return variantData;
    }

    @Override
    public void setTestVariant(@Nullable TestVariant testVariant) {
        this.testVariant = testVariant;
    }

    @Override
    @Nullable
    public TestVariant getTestVariant() {
        return testVariant;
    }

    @Override
    @Nullable
    public UnitTestVariant getUnitTestVariant() {
        return unitTestVariant;
    }

    @Override
    public void setUnitTestVariant(@Nullable UnitTestVariant unitTestVariant) {
        this.unitTestVariant = unitTestVariant;
    }
}