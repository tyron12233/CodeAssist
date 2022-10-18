package com.tyron.builder.model.v2.models

/**
 * The parameter for ModelBuilder to specify what to sync.
 *
 * This interface is implemented and instantiated on the fly by Gradle when using
 * [org.gradle.tooling.BuildController.findModel]
 */
interface ModelBuilderParameter {

    /**
     * The name of the variant for which to return [VariantDependencies]
     */
    var variantName: String
}
