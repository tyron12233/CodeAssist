package com.tyron.builder.gradle.internal.services

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.file.*
import org.gradle.api.provider.*
import java.io.File
import java.util.concurrent.Callable

interface VariantServices : BaseServices {

    /**
     * Creates a new property.
     *
     * This should be used for properties used in the new API. If the property is backing an
     * old API that returns T, use [VariantServices.newPropertyBackingDeprecatedApi]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> propertyOf(type: Class<T>, value: T): Property<T>

    /**
     * Creates a new property.
     *
     * This should be used for properties used in the new API. If the property is backing an
     * old API that returns T, use [VariantServices.newPropertyBackingDeprecatedApi]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> propertyOf(type: Class<T>, value: Provider<T>): Property<T>

    /**
     * Creates a new property.
     *
     * This should be used for properties used in the new API. If the property is backing an
     * old API that returns T, use [VariantServices.newPropertyBackingDeprecatedApi]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> propertyOf(type: Class<T>, value: () -> T): Property<T>

    /**
     * Creates a new property.
     *
     * This should be used for properties used in the new API. If the property is backing an
     * old API that returns T, use [VariantServices.newPropertyBackingDeprecatedApi]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> propertyOf(type: Class<T>, value: Callable<T>): Property<T>

    /**
     * Creates a new nullable property.
     *
     * This should be used for properties used in the new API. If the property is backing an
     * old API that returns T, use [VariantServices.newPropertyBackingDeprecatedApi]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> nullablePropertyOf(type: Class<T>, value: T?): Property<T?>

    /**
     * Creates a new nullable property.
     *
     * This should be used for properties used in the new API. If the property is backing an
     * old API that returns T, use [VariantServices.newPropertyBackingDeprecatedApi]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> nullablePropertyOf(type: Class<T>, value: Provider<T?>): Property<T?>

    /**
     * Creates a new [ListProperty].
     *
     * This should be used for properties used in the new API.
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] if
     * [disallowUnsafeRead] is set to true. If false, the property value access will be allowed.
     *
     * [value] will be used to initialize the property value and further changes will be disallowed
     * unless [disallowChanges] is set to true.
     *
     * The [ListProperty] will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     *
     * [disallowUnsafeRead] should be set to true always, unless we need this for backward
     * compatibility. Do not set it to false for new code.
     */
    fun <T> listPropertyOf(
        type: Class<T>,
        value: Collection<T>,
        disallowUnsafeRead: Boolean = true,
    ): ListProperty<T>

    /**
     * Creates a new [ListProperty].
     *
     * This should be used for properties used in the new API.
     *
     * The [ListProperty] will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> listPropertyOf(
        type: Class<T>,
        fillAction: (ListProperty<T>) -> Unit,
    ): ListProperty<T>

    /**
     * Creates a new [SetProperty].
     *
     * This should be used for properties used in the new API.
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The [SetProperty] will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <T> setPropertyOf(type: Class<T>, value: Callable<Collection<T>>): SetProperty<T>

    /**
     * Creates a new [SetProperty].
     *
     * This should be used for properties used in the new API.
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The [SetProperty] will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     *
     * [disallowUnsafeRead] should be set to true always, unless we need this for backward
     * compatibility. Do not set it to false for new code.
     */
    fun <T> setPropertyOf(
        type: Class<T>,
        value: Collection<T>,
        disallowUnsafeRead: Boolean = true,
    ): SetProperty<T>

    /**
     * Creates a new [MapProperty].
     *
     * This should be used for properties used in the new API.
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The [MapProperty] will be marked as [Property.finalizeValueOnRead], and will be locked
     * with [Property.disallowChanges] after the variant API(s) have run.
     */
    fun <K, V> mapPropertyOf(keyType: Class<K>, valueType: Class<V>, value: Map<K, V>, disallowUnsafeRead: Boolean = true): MapProperty<K, V>

    /**
     * Creates a new property that is backing an old API returning T.
     *
     * By default, this property is memoized with [Property.finalizeValueOnRead] but access
     * to the old API getter will require disabling memoization
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be locked with [Property.disallowChanges] after the variant API(s) have
     * run.
     */
    fun <T> newPropertyBackingDeprecatedApi(type: Class<T>, value: T): Property<T>

    /**
     * Creates a new property that is backing an old API returning T.
     *
     * By default, this property is memoized with [Property.finalizeValueOnRead] but access
     * to the old API getter will require disabling memoization
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be locked with [Property.disallowChanges] after the variant API(s) have
     * run.
     */
    fun <T> newPropertyBackingDeprecatedApi(type: Class<T>, value: Callable<T>): Property<T>

    /**
     * Creates a new property that is backing an old API returning T.
     *
     * By default, this property is memoized with [Property.finalizeValueOnRead] but access
     * to the old API getter will require disabling memoization
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be locked with [Property.disallowChanges] after the variant API(s) have
     * run.
     */
    fun <T> newPropertyBackingDeprecatedApi(type: Class<T>, value: Provider<T>): Property<T>

    /**
     * Creates a new property that is backing an old API returning T.
     *
     * By default, this property is memoized with [Property.finalizeValueOnRead] but access
     * to the old API getter will require disabling memoization
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be locked with [Property.disallowChanges] after the variant API(s) have
     * run.
     */
    fun <T> newNullablePropertyBackingDeprecatedApi(type: Class<T>, value: Provider<T?>): Property<T?>

    /**
     * Creates a new provider that is backing an old API returning T.
     *
     * By default, this property is memoized with [Property.finalizeValueOnRead] but access
     * to the old API getter will require disabling memoization
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * The property will be locked with [Property.disallowChanges] after the variant API(s) have
     * run.
     */
    fun <T> newProviderBackingDeprecatedApi(type: Class<T>, value: Provider<T>): Provider<T>

    /**
     * Creates a memoized Provider around the given provider
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     *
     * [disallowUnsafeRead] should be set to true always, unless we need this for backward
     * compatibility. Do not set it to false for new code.
     */
    fun <T> providerOf(
        type: Class<T>,
        value: Provider<T>,
        id: String = "",
        disallowUnsafeRead: Boolean = true,
    ): Provider<T>

    /**
     * Creates a memoized Provider around the given provider
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     */
    fun <T> nullableProviderOf(type: Class<T>, value: Provider<T?>): Provider<T?>

    /**
     * Creates a memoized [Provider] of [Set] around the given provider of [Iterable]
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     */
    fun <T> setProviderOf(type: Class<T>, value: Provider<out Iterable<T>?>): Provider<Set<T>?>

    /**
     * Creates a memoized [Provider] of [Set] around the provided [Iterable] of values.
     *
     * During configuration the property will be marked as [Property.disallowUnsafeRead] to disallow
     * unsafe reads (which will also finalize the value on read).
     */
    fun <T> setProviderOf(type: Class<T>, value: Iterable<T>?): Provider<Set<T>?>

    fun <T> provider(callable: Callable<T>): Provider<T>

    fun toRegularFileProvider(file: File): Provider<RegularFile>

    fun <T : Named> named(type: Class<T>, name: String): T

    fun fileCollection(): ConfigurableFileCollection
    fun fileCollection(vararg files: Any): ConfigurableFileCollection
    fun fileTree(dir: Any): ConfigurableFileTree

    fun regularFileProperty(): RegularFileProperty
    fun directoryProperty(): DirectoryProperty
    fun fileTree(): ConfigurableFileTree

    fun <T> domainObjectContainer(type: Class<T>, factory: NamedDomainObjectFactory<T>): NamedDomainObjectContainer<T>

    fun lockProperties()

    //
    // All the following APIs should only be used for obtaining providers and properties that will
    // never be accessible through any public APIs (deprecated or not).
    //

    /**
     * Creates a new [ListProperty] of [T] which is not protected and will not be automatically
     * locked.
     */
    fun <T> newListPropertyForInternalUse(type: Class<T>): ListProperty<T>
}