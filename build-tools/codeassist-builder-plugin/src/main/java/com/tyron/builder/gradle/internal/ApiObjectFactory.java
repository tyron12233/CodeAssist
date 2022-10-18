package com.tyron.builder.gradle.internal;

import static com.tyron.builder.core.ComponentTypeImpl.ANDROID_TEST;
import static com.tyron.builder.core.ComponentTypeImpl.UNIT_TEST;

import com.android.annotations.NonNull;
import com.tyron.builder.VariantOutput;
import com.tyron.builder.gradle.BaseExtension;
import com.tyron.builder.gradle.TestedAndroidConfig;
import com.tyron.builder.gradle.internal.api.ApkVariantOutputImpl;
import com.tyron.builder.gradle.internal.api.BaseVariantImpl;
import com.tyron.builder.gradle.internal.api.LibraryVariantOutputImpl;
import com.tyron.builder.gradle.internal.api.ReadOnlyObjectProvider;
import com.tyron.builder.gradle.internal.api.TestVariantImpl;
import com.tyron.builder.gradle.internal.api.TestedVariant;
import com.tyron.builder.gradle.internal.api.UnitTestVariantImpl;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.VariantCreationConfig;
import com.tyron.builder.gradle.internal.crash.ExternalApiUsageException;
import com.tyron.builder.gradle.internal.dsl.VariantOutputFactory;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.gradle.internal.variant.BaseVariantData;
import com.tyron.builder.gradle.internal.variant.VariantFactory;

/**
 * Factory to create ApiObject from VariantData.
 */
public class ApiObjectFactory {
    @NonNull private final BaseExtension extension;
    @NonNull private final VariantFactory<?, ?, ?> variantFactory;
    @NonNull private final DslServices dslServices;

    @NonNull
    private final ReadOnlyObjectProvider readOnlyObjectProvider = new ReadOnlyObjectProvider();

    public ApiObjectFactory(
            @NonNull BaseExtension extension,
            @NonNull VariantFactory<?, ?, ?> variantFactory,
            @NonNull DslServices dslServices) {
        this.extension = extension;
        this.variantFactory = variantFactory;
        this.dslServices = dslServices;
    }

    public BaseVariantImpl create(@NonNull VariantCreationConfig variant) {
        BaseVariantData variantData = variant.getOldVariantApiLegacySupport().getVariantData();

        BaseVariantImpl variantApi =
                variantFactory.createVariantApi(variant, variantData, readOnlyObjectProvider);
        if (variantApi == null) {
            return null;
        }

        if (variantFactory.getComponentType().getHasTestComponents()) {

//            ComponentCreationConfig androidTestVariantProperties =
//                    variant.getTestComponents().get(ANDROID_TEST);
//
//            if (androidTestVariantProperties != null) {
//                TestVariantImpl androidTestVariant =
//                        dslServices.newInstance(
//                                TestVariantImpl.class,
//                                androidTestVariantProperties
//                                        .getOldVariantApiLegacySupport()
//                                        .getVariantData(),
//                                androidTestVariantProperties,
//                                variantApi,
//                                dslServices,
//                                readOnlyObjectProvider,
//                                dslServices.domainObjectContainer(VariantOutput.class));
//                createVariantOutput(androidTestVariantProperties, androidTestVariant);
//
//                ((TestedAndroidConfig) extension).getTestVariants().add(androidTestVariant);
//                ((TestedVariant) variantApi).setTestVariant(androidTestVariant);
//            }
//
//            ComponentCreationConfig unitTestVariantProperties =
//                    variant.getTestComponents().get(UNIT_TEST);
//
//            if (unitTestVariantProperties != null) {
//                UnitTestVariantImpl unitTestVariant =
//                        dslServices.newInstance(
//                                UnitTestVariantImpl.class,
//                                unitTestVariantProperties
//                                        .getOldVariantApiLegacySupport()
//                                        .getVariantData(),
//                                unitTestVariantProperties,
//                                variantApi,
//                                dslServices,
//                                readOnlyObjectProvider,
//                                dslServices.domainObjectContainer(VariantOutput.class));
//
//                ((TestedAndroidConfig) extension).getUnitTestVariants().add(unitTestVariant);
//                ((TestedVariant) variantApi).setUnitTestVariant(unitTestVariant);
//            }
        }

        createVariantOutput(variant, variantApi);

        try {
            // Only add the variant API object to the domain object set once it's been fully
            // initialized.
            extension.addVariant(variantApi);
        } catch (Throwable t) {
            // Adding variant to the collection will trigger user-supplied callbacks
            throw new ExternalApiUsageException(t);
        }

        return variantApi;
    }

    private void createVariantOutput(
            @NonNull ComponentCreationConfig component, @NonNull BaseVariantImpl variantApi) {

        VariantOutputFactory variantOutputFactory =
                new VariantOutputFactory(
                        (component.getComponentType().isAar())
                                ? LibraryVariantOutputImpl.class
                                : ApkVariantOutputImpl.class,
                        dslServices,
                        extension,
                        variantApi,
                        component.getComponentType(),
                        component.getTaskContainer());

        component
                .getOutputs()
                .forEach(
                        // pass the new api variant output object so the override method can
                        // delegate to the new location.
                        variantOutputFactory::create);
    }
}
