package com.tyron.builder.gradle

import com.tyron.builder.gradle.api.ApplicationVariant
import com.tyron.builder.gradle.api.BaseVariant
import com.tyron.builder.gradle.api.BaseVariantOutput
import com.tyron.builder.gradle.internal.ExtraModelInfo
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig
import org.gradle.api.DomainObjectSet
import org.gradle.api.NamedDomainObjectContainer

/**
 * The `android` extension for application plugins.
 *
 *
 * For the base module, see [com.tyron.builder.gradle.BaseExtension]
 *
 *
 * For optional apks, this class is used directly.
 */
abstract class AbstractAppExtension(
    dslServices: DslServices,
    bootClasspathConfig: BootClasspathConfig,
    buildOutputs: NamedDomainObjectContainer<BaseVariantOutput>,
    sourceSetManager: SourceSetManager,
    extraModelInfo: ExtraModelInfo,
    isBaseModule: Boolean
) : TestedExtension(
    dslServices,
    bootClasspathConfig,
    buildOutputs,
    sourceSetManager,
    extraModelInfo,
    isBaseModule
) {

    /**
     * Returns a collection of [build variants](https://developer.android.com/studio/build/build-variants.html) that
     * the app project includes.
     *
     *
     * To process elements in this collection, you should use the [
 * `all`](https://docs.gradle.org/current/javadoc/org/gradle/api/DomainObjectCollection.html#all(org.gradle.api.Action)) iterator. That's because the plugin populates this collection only after
     * the project is evaluated. Unlike the `each` iterator, using `all`
     * processes future elements as the plugin creates them.
     *
     *
     * The following sample iterates through all `applicationVariants` elements to [inject a
 * build variable into the manifest](https://developer.android.com/studio/build/manifest-build-variables.html):
     *
     * <pre>
     * android.applicationVariants.all { variant -&gt;
     * def mergedFlavor = variant.getMergedFlavor()
     * // Defines the value of a build variable you can use in the manifest.
     * mergedFlavor.manifestPlaceholders = [hostName:"www.example.com/${variant.versionName}"]
     * }
    </pre> *
     */
    val applicationVariants: DomainObjectSet<ApplicationVariant> =
        dslServices.domainObjectSet(ApplicationVariant::class.java)

    override fun addVariant(variant: BaseVariant) {
        applicationVariants.add(variant as ApplicationVariant)
    }
}
