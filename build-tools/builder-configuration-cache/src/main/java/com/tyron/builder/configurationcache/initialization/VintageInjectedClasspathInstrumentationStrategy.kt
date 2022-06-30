package com.tyron.builder.configurationcache.initialization

import com.tyron.builder.internal.classpath.CachedClasspathTransformer


class VintageInjectedClasspathInstrumentationStrategy : AbstractInjectedClasspathInstrumentationStrategy() {
    override fun whenAgentPresent(): CachedClasspathTransformer.StandardTransform {
        // For now, disable the instrumentation
        return CachedClasspathTransformer.StandardTransform.None
    }
}
