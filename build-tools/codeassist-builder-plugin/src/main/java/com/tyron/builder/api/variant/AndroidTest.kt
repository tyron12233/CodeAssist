package com.tyron.builder.api.variant

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.Serializable

/**
 * Properties for the android test Variant of a module.
 */
interface AndroidTest : GeneratesTestApk, TestComponent, HasAndroidResources {

    /**
     * Variant's application ID as present in the final manifest file of the APK.
     */
    override val applicationId: Property<String>

    /**
     * The namespace of the generated R and BuildConfig classes. Also, the namespace used to resolve
     * any relative class names that are declared in the AndroidManifest.xml.
     */
    val namespace: Provider<String>

    /**
     * Variant's [BuildConfigField] which will be generated in the BuildConfig class.
     */
    val buildConfigFields: MapProperty<String, out BuildConfigField<out Serializable>>

    /**
     * Variant's signingConfig, initialized by the corresponding DSL element.
     * @return Variant's config or null if the variant is not configured for signing.
     */
    val signingConfig: SigningConfig?

    /**
     * List of proguard configuration files for this variant. The list is initialized from the
     * corresponding DSL element, and cannot be queried at configuration time. At configuration time,
     * you can only add new elements to the list.
     */
    val proguardFiles: ListProperty<RegularFile>
}
