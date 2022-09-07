package com.tyron.builder.gradle.internal.services

import com.tyron.builder.gradle.options.BooleanOption
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.file.*
import org.gradle.api.provider.*
import java.io.File
import java.util.concurrent.Callable

class VariantServicesImpl(
    projectServices: ProjectServices,
    private val forUnitTesting: Boolean = false,
): BaseServicesImpl(projectServices),
    VariantServices {
    // list of properties to lock when [.lockProperties] is called.
    private val properties = mutableListOf<HasConfigurableValue>()
    // whether the properties have been locked already
    private var propertiesLockStatus = false

    // flag to know whether to enable compatibility mode for properties that back old API returning the
    // direct value.
    private val compatibilityMode = projectServices.projectOptions[BooleanOption.ENABLE_LEGACY_API]

    override fun <T> propertyOf(type: Class<T>, value: T): Property<T> {
        return initializeProperty(type).also {
            it.set(value)
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> propertyOf(type: Class<T>, value: Provider<T>): Property<T> {
        return initializeProperty(type).also {
            it.set(value)
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> propertyOf(type: Class<T>, value: () -> T): Property<T> {
        return initializeProperty(type).also {
            it.set(projectServices.providerFactory.provider(value))
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> propertyOf(type: Class<T>, value: Callable<T>): Property<T> {
        return initializeProperty(type).also {
            it.set(projectServices.providerFactory.provider(value))
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> nullablePropertyOf(type: Class<T>, value: T?): Property<T?> {
        return initializeNullableProperty(type).also {
            it.set(value)
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> nullablePropertyOf(type: Class<T>, value: Provider<T?>): Property<T?> {
        return initializeNullableProperty(type).also {
            it.set(value)
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> listPropertyOf(
        type: Class<T>,
        value: Collection<T>,
        disallowUnsafeRead: Boolean,
    ): ListProperty<T> {
        return projectServices.objectFactory.listProperty(type).also {
            it.set(value)
            it.finalizeValueOnRead()
            delayedLock(it)
            if (disallowUnsafeRead && !forUnitTesting) {
                it.disallowUnsafeRead()
            }
        }
    }

    override fun <T> listPropertyOf(
        type: Class<T>,
        fillAction: (ListProperty<T>) -> Unit,
    ): ListProperty<T> {
        return projectServices.objectFactory.listProperty(type).also {
            fillAction(it)
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> setPropertyOf(type: Class<T>, value: Callable<Collection<T>>): SetProperty<T> {
        return projectServices.objectFactory.setProperty(type).also {
            it.set(projectServices.providerFactory.provider(value))
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> setPropertyOf(
        type: Class<T>,
        value: Collection<T>,
        disallowUnsafeRead: Boolean
    ): SetProperty<T> {
        return projectServices.objectFactory.setProperty(type).also {
            it.set(value)
            it.finalizeValueOnRead()
            if (disallowUnsafeRead && !forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <K, V> mapPropertyOf(
        keyType: Class<K>,
        valueType: Class<V>,
        value: Map<K, V>,
        disallowUnsafeRead: Boolean,
    ): MapProperty<K, V> {
        return projectServices.objectFactory.mapProperty(keyType, valueType).also {
            it.set(value)
            it.finalizeValueOnRead()
            if (disallowUnsafeRead && !forUnitTesting) {
                it.disallowUnsafeRead()
            }
            delayedLock(it)
        }
    }

    override fun <T> newPropertyBackingDeprecatedApi(type: Class<T>, value: T): Property<T> {
        return initializeProperty(type).also {
            it.set(value)
            if (!compatibilityMode) {
                it.finalizeValueOnRead()
                if (!forUnitTesting) {
                    it.disallowUnsafeRead()
                }
            }
            delayedLock(it)
        }
    }

    override fun <T> newPropertyBackingDeprecatedApi(type: Class<T>, value: Callable<T>): Property<T> {
        return initializeProperty(type).also {
            it.set(projectServices.providerFactory.provider(value))
            if (!compatibilityMode) {
                it.finalizeValueOnRead()
                if (!forUnitTesting) {
                    it.disallowUnsafeRead()
                }
            }
            delayedLock(it)
        }
    }

    override fun <T> newPropertyBackingDeprecatedApi(type: Class<T>, value: Provider<T>): Property<T> {
        return initializeProperty(type).also {
            it.set(value)
            if (!compatibilityMode) {
                it.finalizeValueOnRead()
                if (!forUnitTesting) {
                    it.disallowUnsafeRead()
                }
            }
            delayedLock(it)
        }
    }

    override fun <T> newProviderBackingDeprecatedApi(type: Class<T>, value: Provider<T>): Provider<T> {
        return initializeProperty(type).also {
            it.set(value)
            it.disallowChanges()
            if (!compatibilityMode) {
                it.finalizeValueOnRead()
                if (!forUnitTesting) {
                    it.disallowUnsafeRead()
                }
            }
        }
    }

    override fun <T> newListPropertyForInternalUse(type: Class<T>): ListProperty<T> {
        return initializeListProperty(type)
    }

    override fun <T> newNullablePropertyBackingDeprecatedApi(type: Class<T>, value: Provider<T?>): Property<T?> {
        return initializeNullableProperty(type).also {
            it.set(value)
            if (!compatibilityMode) {
                it.finalizeValueOnRead()
                if (!forUnitTesting) {
                    it.disallowUnsafeRead()
                }
            }
            delayedLock(it)
        }
    }

    override fun <T> providerOf(
        type: Class<T>,
        value: Provider<T>,
        id: String,
        disallowUnsafeRead: Boolean,
    ): Provider<T> {
        return initializeProperty(type).also {
            it.set(value)
            it.disallowChanges()
            it.finalizeValueOnRead()
            if (disallowUnsafeRead && !forUnitTesting) {
                it.disallowUnsafeRead()
            }
        }
    }

    override fun <T> nullableProviderOf(type: Class<T>, value: Provider<T?>): Provider<T?> {
        return initializeProperty(type).also {
            it.set(value)
            it.disallowChanges()
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
        }
    }

    override fun <T> setProviderOf(type: Class<T>, value: Provider<out Iterable<T>?>): Provider<Set<T>?> {
        return projectServices.objectFactory.setProperty(type).also {
            it.set(value)
            it.disallowChanges()
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
        }
    }

    override fun <T> setProviderOf(type: Class<T>, value: Iterable<T>?): Provider<Set<T>?> {
        return projectServices.objectFactory.setProperty(type).also {
            it.set(value)
            it.disallowChanges()
            it.finalizeValueOnRead()
            if (!forUnitTesting) {
                it.disallowUnsafeRead()
            }
        }
    }

    override fun regularFileProperty(): RegularFileProperty =
        projectServices.objectFactory.fileProperty().also {
            properties.add(it)
        }

    override fun directoryProperty(): DirectoryProperty =
        projectServices.objectFactory.directoryProperty().also {
            properties.add(it)
        }

    override fun fileTree(): ConfigurableFileTree =
        projectServices.objectFactory.fileTree()

    override fun <T> provider(callable: Callable<T>): Provider<T> {
        return projectServices.providerFactory.provider(callable)
    }

    override fun toRegularFileProvider(file: File): Provider<RegularFile> {
        return projectServices.projectLayout.file(projectServices.providerFactory.provider { file })
    }

    override fun <T : Named> named(type: Class<T>, name: String): T =
        projectServices.objectFactory.named(type, name)

    override fun fileCollection(): ConfigurableFileCollection =
        projectServices.objectFactory.fileCollection()

    override fun fileCollection(vararg files: Any): ConfigurableFileCollection =
        projectServices.objectFactory.fileCollection().from(*files)

    override fun fileTree(dir: Any): ConfigurableFileTree {
        val result = projectServices.objectFactory.fileTree().setDir(dir)

        // workaround issue in Gradle <=6.3 where setDir does not set dependencies
        // TODO remove when 6.4 ships
        if (dir is Provider<*>) {
            result.builtBy(dir)
        }
        return result
    }

    override fun lockProperties() {
        for (property in properties) {
            property.disallowChanges()
        }
        properties.clear()
        propertiesLockStatus = true
    }

    override fun <T> domainObjectContainer(type: Class<T>, factory: NamedDomainObjectFactory<T>) =
        projectServices.objectFactory.domainObjectContainer(type, factory)!!

    // register a property to be locked later.
    // if the properties have already been locked, the property is locked right away.
    // (this can happen for objects that are lazily created)
    private fun delayedLock(property: HasConfigurableValue) {
        if (propertiesLockStatus) {
            property.disallowChanges()
        } else {
            properties.add(property)
        }
    }

    private fun <T> initializeProperty(type: Class<T>): Property<T> =
        projectServices.objectFactory.property(type)

    private fun <T> initializeListProperty(type: Class<T>): ListProperty<T> =
        projectServices.objectFactory.listProperty(type)

    private fun <T> initializeNullableProperty(type: Class<T>): Property<T?> =
        projectServices.objectFactory.property(type)
}
