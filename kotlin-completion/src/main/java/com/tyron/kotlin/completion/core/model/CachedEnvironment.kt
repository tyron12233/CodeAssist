package com.tyron.kotlin.completion.core.model

import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.ZipHandler
import java.util.concurrent.ConcurrentHashMap

class CachedEnvironment<T: Any, E : KotlinCoreEnvironment> {
    private val environmentLock = Any()

    private val environmentCache = ConcurrentHashMap<T, E>()
    private val ideaProjectToEclipseResource = ConcurrentHashMap<Project, T>()

    fun putEnvironment(resource: T, environment: E): Unit = synchronized(environmentLock) {
        environmentCache[resource] = environment
        ideaProjectToEclipseResource[environment.project] = resource
    }

    fun getOrCreateEnvironment(resource: T, createEnvironment: (T) -> E): E = synchronized(environmentLock) {
        environmentCache.getOrPut(resource) {
            createEnvironment(resource).also { ideaProjectToEclipseResource[it.project] = resource }
        }
    }

    fun removeEnvironment(resource: T) = synchronized(environmentLock) {
        removeEnvironmentInternal(resource)
    }

    fun removeAllEnvironments() = synchronized(environmentLock) {
        environmentCache.keys.toList().forEach {
            removeEnvironmentInternal(it)
        }
    }

    private fun removeEnvironmentInternal(resource: T) {
        environmentCache.remove(resource)?.also {
            ideaProjectToEclipseResource.remove(it.project)

            Disposer.dispose(it.projectEnvironment.parentDisposable)
            ZipHandler.clearFileAccessorCache()
        }
    }

    fun replaceEnvironment(resource: T, createEnvironment: (T) -> E): E = synchronized(environmentLock) {
        removeEnvironment(resource)
        getOrCreateEnvironment(resource, createEnvironment)
    }

    fun getEclipseResource(ideaProject: Project): T? = synchronized(environmentLock) {
        ideaProjectToEclipseResource[ideaProject]
    }
}