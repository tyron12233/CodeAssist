package com.tyron.builder.gradle.internal.services

import com.tyron.builder.gradle.internal.SdkComponentsBuildService
import org.gradle.api.DomainObjectSet
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Services for the DSL objects.
 *
 * This contains whatever is needed by all the DSL objects.
 *
 * This is meant to be transient and only available by the DSL objects. Other stages of the
 * plugin will use different services objects.
 */
interface DslServices : BaseServices {

    val logger: Logger
    val buildDirectory: DirectoryProperty

    @Deprecated("Should not be used in new DSL object. Only for older DSL objects.")
    val sdkComponents: Provider<SdkComponentsBuildService>
    @Deprecated("Should not be used in new DSL object. Only for older DSL objects.")
    val versionedSdkLoaderService: VersionedSdkLoaderService

    fun <T> domainObjectSet(type: Class<T>): DomainObjectSet<T>
    fun <T> domainObjectContainer(
        type: Class<T>,
        factory: NamedDomainObjectFactory<T>
    ): NamedDomainObjectContainer<T>
    fun <T> domainObjectContainer(type: Class<T>): NamedDomainObjectContainer<T>
    fun <T> polymorphicDomainObjectContainer(
        type: Class<T>
    ): ExtensiblePolymorphicDomainObjectContainer<T>

    @Deprecated("do not use. DSL elements should not use Property<T> objects")
    fun <T> property(type: Class<T>): Property<T>
    @Deprecated("do not use. DSL elements should not use DirectoryProperty objects")
    fun directoryProperty(): DirectoryProperty

    fun <T> provider(type: Class<T>, value: T?): Provider<T>

    fun <T: Any> newDecoratedInstance(dslClass: Class<T>, vararg args: Any) : T
}