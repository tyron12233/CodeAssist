package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.PublishingOptions

abstract class PublishingOptionsImpl : PublishingOptions {

    internal var withSourcesJar: Boolean = false
    internal var withJavadocJar: Boolean = false

    override fun withSourcesJar() {
        withSourcesJar = true
    }

    override fun withJavadocJar() {
        withJavadocJar = true
    }
}