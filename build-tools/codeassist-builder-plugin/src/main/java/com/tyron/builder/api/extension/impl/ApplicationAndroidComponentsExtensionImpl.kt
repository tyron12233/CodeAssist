package com.tyron.builder.api.extension.impl

import com.tyron.builder.api.dsl.ApplicationExtension
import com.tyron.builder.api.dsl.SdkComponents
import com.tyron.builder.api.variant.ApplicationAndroidComponentsExtension
import com.tyron.builder.api.variant.ApplicationVariant
import com.tyron.builder.api.variant.ApplicationVariantBuilder
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

open class ApplicationAndroidComponentsExtensionImpl @Inject constructor(
        dslServices: DslServices,
        sdkComponents: SdkComponents,
        variantApiOperations: VariantApiOperationsRegistrar<ApplicationExtension, ApplicationVariantBuilder, ApplicationVariant>,
        applicationExtension: ApplicationExtension
): ApplicationAndroidComponentsExtension,
        AndroidComponentsExtensionImpl<ApplicationExtension, ApplicationVariantBuilder, ApplicationVariant>(
                dslServices,
                sdkComponents,
                variantApiOperations,
                applicationExtension
        )
