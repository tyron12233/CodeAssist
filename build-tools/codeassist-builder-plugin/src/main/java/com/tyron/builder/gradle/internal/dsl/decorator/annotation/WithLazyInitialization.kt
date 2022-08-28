package com.tyron.builder.gradle.internal.dsl.decorator.annotation

/**
 * Allows to set defaults or manipulate fields that are implemented by the [DslDecorator].
 *
 * The class should have a protected method [methodName] which will be invoked last in the decorated
 * constructor.
 */
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class WithLazyInitialization(val methodName: String)