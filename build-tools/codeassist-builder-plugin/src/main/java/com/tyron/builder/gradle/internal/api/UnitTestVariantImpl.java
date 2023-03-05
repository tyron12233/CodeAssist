package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.api.BaseVariantOutput;
import com.tyron.builder.gradle.api.UnitTestVariant;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.variant.BaseVariantData;
import com.tyron.builder.gradle.internal.variant.TestVariantData;
import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectContainer;

/**
 * External API wrapper around the {@link TestVariantData}, for unit testing variants.
 */
public class UnitTestVariantImpl extends BaseVariantImpl implements UnitTestVariant {

    @NonNull
    private final TestVariantData variantData;
    @NonNull
    private final TestedVariant testedVariant;

    @Inject
    public UnitTestVariantImpl(
            @NonNull TestVariantData variantData,
            @NonNull ComponentCreationConfig component,
            @NonNull TestedVariant testedVariant,
            @NonNull DslServices services,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider,
            @NonNull NamedDomainObjectContainer<BaseVariantOutput> outputs) {
        super(component, services, readOnlyObjectProvider, outputs);

        this.variantData = variantData;
        this.testedVariant = testedVariant;
    }

    @NonNull
    @Override
    protected BaseVariantData getVariantData() {
        return variantData;
    }

    @NonNull
    @Override
    public TestedVariant getTestedVariant() {
        return testedVariant;
    }
}
