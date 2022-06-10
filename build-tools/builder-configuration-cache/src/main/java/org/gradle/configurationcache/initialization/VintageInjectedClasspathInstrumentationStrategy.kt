package org.gradle.configurationcache.initialization

import org.gradle.internal.classpath.CachedClasspathTransformer


class VintageInjectedClasspathInstrumentationStrategy : AbstractInjectedClasspathInstrumentationStrategy() {
    override fun whenAgentPresent(): CachedClasspathTransformer.StandardTransform {
        // For now, disable the instrumentation
        return CachedClasspathTransformer.StandardTransform.None
    }
}
