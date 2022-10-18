package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.JniLibsPackagingOptions

abstract class JniLibsPackagingOptionsImpl : JniLibsPackagingOptions {
    // support excludes += 'foo' syntax in groovy
    abstract fun setExcludes(patterns: Set<String>)

    // support pickFirsts += 'foo' syntax in groovy
    abstract fun setPickFirsts(patterns: Set<String>)

    // support keepDebugSymbols += 'foo' syntax in groovy
    abstract fun setKeepDebugSymbols(patterns: Set<String>)
}