package com.tyron.builder.gradle.internal.services

import com.tyron.builder.gradle.internal.dsl.decorator.androidPluginDslDecorator
import org.gradle.api.DomainObjectSet
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

class DslServicesImpl constructor(
    projectServices: ProjectServices,
) : BaseServicesImpl(projectServices), DslServices {

    override fun <T> domainObjectContainer(
        type: Class<T>,
        factory: NamedDomainObjectFactory<T>
    ): NamedDomainObjectContainer<T> =
        projectServices.objectFactory.domainObjectContainer(type, factory)

    override fun <T> domainObjectContainer(type: Class<T>): NamedDomainObjectContainer<T> =
        projectServices.objectFactory.domainObjectContainer(type)

    override fun <T> polymorphicDomainObjectContainer(
        type: Class<T>
    ): ExtensiblePolymorphicDomainObjectContainer<T> =
        projectServices.objectFactory.polymorphicDomainObjectContainer(type)

    override fun <T> property(type: Class<T>): Property<T> =
        projectServices.objectFactory.property(type)

    override fun directoryProperty(): DirectoryProperty =
        projectServices.objectFactory.directoryProperty()

    override fun <T> provider(type: Class<T>, value: T?): Provider<T> =
        projectServices.objectFactory.property(type).also {
            it.set(value)
        }

    override val buildDirectory: DirectoryProperty
        get() = projectServices.projectLayout.buildDirectory

    override fun <T> domainObjectSet(type: Class<T>): DomainObjectSet<T> =
        projectServices.objectFactory.domainObjectSet(type)


    override val logger: Logger
        get() = projectServices.logger

    override fun <T: Any> newDecoratedInstance(dslClass: Class<T>, vararg args: Any) : T {
        return newInstance(androidPluginDslDecorator.decorate(dslClass), *args)
    }
}