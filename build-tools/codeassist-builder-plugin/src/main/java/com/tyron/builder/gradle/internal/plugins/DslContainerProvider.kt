package com.tyron.builder.gradle.internal.plugins

import com.tyron.builder.api.dsl.ApkSigningConfig
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.DefaultConfig
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.dsl.AgpDslLockedException
import com.tyron.builder.gradle.internal.dsl.Lockable
import org.gradle.api.NamedDomainObjectContainer

/**
 * Provider of the various containers that the extensions uses.
 *
 * This is using Type parameters as the objects in the container will vary depending on
 * the type of the plugin.
 *
 */
interface DslContainerProvider<
        DefaultConfigT : DefaultConfig,
        BuildTypeT : BuildType,
        ProductFlavorT : ProductFlavor,
        SigningConfigT : ApkSigningConfig> {

    val defaultConfig: DefaultConfigT

    val buildTypeContainer: NamedDomainObjectContainer<BuildTypeT>
    val productFlavorContainer: NamedDomainObjectContainer<ProductFlavorT>
    val signingConfigContainer: NamedDomainObjectContainer<SigningConfigT>

    val sourceSetManager: SourceSetManager

    fun lock() {
        (defaultConfig as Lockable).lock()
        buildTypeContainer.all { (it as Lockable).lock() }
        productFlavorContainer.all { (it as Lockable).lock() }
        signingConfigContainer.all { (it as Lockable).lock() }
        buildTypeContainer.whenObjectAdded { failLocked("build types") }
        productFlavorContainer.whenObjectAdded { failLocked("product flavors") }
        signingConfigContainer.whenObjectAdded { failLocked("signing configs") }
    }

    private fun failLocked(collectionName: String): Nothing  {
        throw AgpDslLockedException(
            "It is too late to add new $collectionName\n" +
                    "They have already been used to configure this project.\n" +
                    "Consider moving this call to finalizeDsl or during evaluation."
        );
    }
}