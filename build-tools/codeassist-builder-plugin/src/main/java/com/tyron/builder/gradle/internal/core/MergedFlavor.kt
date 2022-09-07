package com.tyron.builder.gradle.internal.core

import com.tyron.builder.gradle.internal.api.BaseVariantImpl
import com.tyron.builder.gradle.internal.dsl.VectorDrawablesOptions
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.core.AbstractProductFlavor
import com.tyron.builder.core.DefaultVectorDrawablesOptions
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.model.BaseConfig
import com.tyron.builder.model.ProductFlavor
import com.google.common.collect.Lists
import com.tyron.builder.gradle.errors.DeprecationReporter
import org.gradle.api.provider.Property

/**
 * The merger of the default config and all of a variant's flavors (if any)
 */
class MergedFlavor(
    name: String,
    private val _applicationId: Property<String>,
    private val services: VariantServices
) : AbstractProductFlavor(name), InternalBaseVariant.MergedFlavor {

    override var dimension: String? = null
    override var renderscriptSupportModeEnabled: Boolean? = null
    override var renderscriptSupportModeBlasEnabled: Boolean? = null
    override var renderscriptNdkModeEnabled: Boolean? = null
    override var testApplicationId: String? = null
    override var testInstrumentationRunner: String? = null
    override var testHandleProfiling: Boolean? = null
    override var testFunctionalTest: Boolean? = null
    override var wearAppUnbundled: Boolean? = null
    override var _versionCode: Int? = null
    override var _versionName: String? = null
    override val resourceConfigurations: MutableSet<String> = mutableSetOf()

    // in the merged flavor scenario which is still accessible from the old variant API, we need
    // to reset the value in the VariantProperties which we can do through the DslInfo reference.
    override var applicationId: String?
        get() {
            // consider throwing an exception instead, as this is not reliable.
            services.deprecationReporter
                .reportDeprecatedApi(
                    "Variant.getApplicationId()",
                    "MergedFlavor.getApplicationId()",
                    BaseVariantImpl.USE_PROPERTIES_DEPRECATION_URL,
                    DeprecationReporter.DeprecationTarget.USE_PROPERTIES
                )

            if (!services.projectOptions.get(BooleanOption.ENABLE_LEGACY_API)) {
                services.issueReporter
                    .reportError(
                        IssueReporter.Type.GENERIC,
                        RuntimeException(
                            """
                                Access to deprecated legacy com.tyron.builder.model.ProductFlavor.getApplicationId() requires compatibility mode for Property values in new com.tyron.builder.api.variant.VariantOutput.versionCode
                                ${BooleanOption.ENABLE_LEGACY_API}
                                """.trimIndent()
                        )
                    )
                // return default value
                return null
            }
            return _applicationId.get()
        }
        set(value) {
            setApplicationId(value)
        }

    override fun setApplicationId(applicationId: String?): ProductFlavor {
        _applicationId.set(applicationId)
        return this
    }

    companion object {

        /**
         * Clone a given product flavor.
         *
         * @param productFlavor the flavor to clone.
         * @return a new MergedFlavor instance that is a clone of the flavor.
         */
        @JvmStatic
        fun clone(productFlavor: ProductFlavor, applicationId: Property<String>, services: VariantServices): MergedFlavor {
            val mergedFlavor = MergedFlavor(productFlavor.name, applicationId, services)
            mergedFlavor._initWith(productFlavor)
            return mergedFlavor
        }

        /**
         * Merges the flavors by analyzing the specified one and the list. Flavors whose position in
         * the list is higher will have their values overwritten by the lower-position flavors (in
         * case they have non-null values for some properties). E.g. if flavor at position 1
         * specifies applicationId &quot;my.application&quot;, and flavor at position 0 specifies
         * &quot;sample.app&quot;, merged flavor will have applicationId &quot;sampleapp&quot; (if
         * there are no other flavors overwriting this value). Flavor `lowestPriority`, as the name
         * says, has the lowest priority of them all, and will always be overwritten.
         *
         * @param lowestPriority flavor with the lowest priority
         * @param flavors flavors to merge
         * @return final merged product flavor
         */
        @JvmStatic
        fun mergeFlavors(
            lowestPriority: ProductFlavor,
            flavors: List<ProductFlavor>,
            applicationId: Property<String>,
            services: VariantServices
        ): MergedFlavor {
            val mergedFlavor = clone(lowestPriority, applicationId, services)
            for (flavor in Lists.reverse(flavors)) {
                mergedFlavor.mergeWithHigherPriorityFlavor(flavor)
            }

            /*
             * For variants with product flavor dimensions d1, d2 and flavors f1 of d1 and f2 of d2, we
             * will have final applicationSuffixId suffix(default).suffix(f2).suffix(f1). However, the
             * previous implementation of product flavor merging would produce
             * suffix(default).suffix(f1).suffix(f2). We match that behavior below as we do not want to
             * change application id of developers' applications. The same applies to versionNameSuffix.
             */
            var applicationIdSuffix = lowestPriority.applicationIdSuffix
            var versionNameSuffix = lowestPriority.versionNameSuffix
            for (mFlavor in flavors) {
                applicationIdSuffix = mergeApplicationIdSuffix(
                        mFlavor.applicationIdSuffix, applicationIdSuffix)
                versionNameSuffix = mergeVersionNameSuffix(
                    mFlavor.versionNameSuffix, versionNameSuffix
                )
            }
            mergedFlavor.applicationIdSuffix = applicationIdSuffix
            mergedFlavor.versionNameSuffix = versionNameSuffix

            return mergedFlavor
        }
    }

    private var _vectorDrawables: DefaultVectorDrawablesOptions = DefaultVectorDrawablesOptions()

    override val vectorDrawables: DefaultVectorDrawablesOptions
        get() = _vectorDrawables

    override fun _initWith(that: BaseConfig) {
        super._initWith(that)
        if (that is ProductFlavor) {
            _vectorDrawables = VectorDrawablesOptions.copyOf(that.vectorDrawables)
        }
    }

    override var versionCode: Int?
        get() = super.versionCode
        set(value) {
            // calling setVersionCode results in a sync Error because the manifest merger doesn't pick
            // up the change.
            reportErrorWithWorkaround("versionCode", "versionCodeOverride", value)
        }

    override var versionName: String?
        get() = super.versionName
        set(value) {
            // calling setVersionName results in a sync Error because the manifest merger doesn't pick
            // up the change.
            reportErrorWithWorkaround("versionName", "versionNameOverride", value)
        }

    private fun reportErrorWithWorkaround(
        fieldName: String,
        outputFieldName: String,
        fieldValue: Any?
    ) {
        val formattedFieldValue = if (fieldValue is String) {
            "\"" + fieldValue + "\""
        } else {
            fieldValue.toString()
        }

        val message = """$fieldName cannot be set on a mergedFlavor directly.
                |$outputFieldName can instead be set for variant outputs using the following syntax:
                |android {
                |    applicationVariants.all { variant ->
                |        variant.outputs.each { output ->
                |            output.$outputFieldName = $formattedFieldValue
                |        }
                |    }
                |}""".trimMargin()

        services.issueReporter.reportError(IssueReporter.Type.GENERIC, message)
    }
}
