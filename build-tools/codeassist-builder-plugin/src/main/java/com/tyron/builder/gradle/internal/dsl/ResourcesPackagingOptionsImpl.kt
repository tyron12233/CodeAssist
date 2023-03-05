package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ResourcesPackagingOptions
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.tyron.builder.gradle.internal.packaging.defaultExcludes
import com.tyron.builder.gradle.internal.packaging.defaultMerges
import javax.inject.Inject

abstract class ResourcesPackagingOptionsImpl
@Inject @WithLazyInitialization("lazyInit") constructor() : ResourcesPackagingOptions {

    protected fun lazyInit() {
        setExcludes(defaultExcludes)
        setMerges(defaultMerges)
    }

    // support excludes += 'foo' syntax in groovy
    abstract fun setExcludes(patterns: Set<String>)

    // support pickFirsts += 'foo' syntax in groovy
    abstract fun setPickFirsts(patterns: Set<String>)

    // support merges += 'foo' syntax in groovy
    abstract fun setMerges(patterns: Set<String>)
}
