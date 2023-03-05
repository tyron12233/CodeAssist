package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.HasInitWith
import org.gradle.api.plugins.ExtensionAware

/**
 * Given an extensionAware object, go through all extensions and call initWith if it is present.
 *
 * See BuildTypeTest.initWith
 */
fun initExtensions(from: ExtensionAware, to: ExtensionAware) {
    for (schema in to.extensions.extensionsSchema) {
        if (HasInitWith::class.java.isAssignableFrom(schema.publicType.concreteClass)) {
            val toExtension = to.extensions.getByName(schema.name)
            @Suppress("UNCHECKED_CAST")
            toExtension as HasInitWith<Any>
            from.extensions.findByName(schema.name)?.let { fromExtension ->
                toExtension.initWith(fromExtension)
            }
        }
    }
}