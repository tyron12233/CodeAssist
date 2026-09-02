package dev.ide.android.support

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.platform.ServiceKey

/**
 * A host's shared, cached source of merged [ResourceRepository]s.
 *
 * Parsing a module's resources means re-reading its own `res/` plus every dependency's, so doing it per call
 * (once per completion or analysis pass) is what made the synthetic `R` an OOM risk. A host that keeps a
 * cache registers it under [ANDROID_RESOURCE_REPOSITORY] and every consumer shares that one parse.
 *
 * Implementations return null for a module with no resources, and must be safe to call from analysis threads.
 */
fun interface ResourceRepositorySource {
    fun repository(module: Module, workspace: Workspace): ResourceRepository?
}

/**
 * WORKSPACE-scoped: the shared resource-repository cache for the workspace being asked about.
 *
 * Resolved through the [Workspace] the caller was handed, NOT through any notion of "the currently open
 * project", so a consumer always reads the cache belonging to the model it is working on. Optional by
 * design: resolve it with [Workspace.serviceOrNull] and fall back to parsing directly, which is what a
 * standalone host or a test with no engine behind it gets.
 */
val ANDROID_RESOURCE_REPOSITORY = ServiceKey<ResourceRepositorySource>("android.resourceRepository")
