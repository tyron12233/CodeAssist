package org.gradle.configurationcache.initialization

import org.gradle.internal.classpath.CachedClasspathTransformer
import org.gradle.plugin.use.resolve.service.internal.InjectedClasspathInstrumentationStrategy
import java.lang.management.ManagementFactory


abstract class AbstractInjectedClasspathInstrumentationStrategy : InjectedClasspathInstrumentationStrategy {
    override fun getTransform(): CachedClasspathTransformer.StandardTransform {
        val isAgentPresent = ManagementFactory.getRuntimeMXBean().inputArguments.find { it.startsWith("-javaagent:") } != null
        return if (isAgentPresent) {
            // Currently, the build logic instrumentation can interfere with Java agents, such as Jacoco
            // So, disable or fail or whatever based on which execution modes are enabled
            whenAgentPresent()
        } else {
            CachedClasspathTransformer.StandardTransform.BuildLogic
        }
    }

    abstract fun whenAgentPresent(): CachedClasspathTransformer.StandardTransform
}
