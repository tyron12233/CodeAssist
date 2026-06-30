package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.ContentRole
import dev.ide.ui.backend.ModuleService
import dev.ide.ui.backend.UiBuildFeatures
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiModuleTypeOption
import dev.ide.ui.backend.UiMissingProguardFile
import dev.ide.ui.backend.UiSourceRootRole
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ModuleService] over the engine: source sets/roots, language level + facet config, add/remove modules,
 *  proguard files. Mutations that change the tree bump the file-system epoch. */
internal class ModuleBackend(private val ctx: BackendContext) : ModuleService {

    override fun moduleSourceSets(moduleName: String): List<String> =
        ctx.services.modules().firstOrNull { it.name == moduleName }
            ?.let { ctx.services.moduleService.sourceSetNamesOf(it) } ?: emptyList()

    override fun addSourceRoot(
        moduleName: String, sourceSetName: String, dirName: String, role: UiSourceRootRole
    ): String? {
        val roles = when (role) {
            UiSourceRootRole.Source -> setOf(ContentRole.SOURCE)
            UiSourceRootRole.Resource -> setOf(ContentRole.RESOURCE)
            UiSourceRootRole.AndroidRes -> setOf(ContentRole.ANDROID_RES)
            UiSourceRootRole.Assets -> setOf(ContentRole.ASSETS)
            UiSourceRootRole.Aidl -> setOf(ContentRole.AIDL)
        }
        val created =
            ctx.services.moduleService.addSourceRoot(moduleName, sourceSetName, dirName.trim().trim('/'), roles)
                ?: return null
        ctx.bumpFileSystemEpoch()
        return created.toString()
    }

    override fun removeSourceRoot(
        moduleName: String, sourceSetName: String, rootPath: String
    ): Boolean {
        // The model stores roots relative to the module dir; translate the absolute tree path back.
        val module = ctx.services.modules().firstOrNull { it.name == moduleName } ?: return false
        val moduleDir = ctx.services.moduleRoot(module) ?: return false
        val rel = runCatching {
            moduleDir.toAbsolutePath().normalize()
                .relativize(Paths.get(rootPath).toAbsolutePath().normalize()).toString()
        }.getOrNull() ?: rootPath
        val ok = ctx.services.moduleService.removeSourceRoot(moduleName, sourceSetName, rel)
        if (ok) ctx.bumpFileSystemEpoch()
        return ok
    }

    override fun addSourceSet(moduleName: String, name: String): Boolean {
        val ok = ctx.services.moduleService.addSourceSet(moduleName, name.trim())
        if (ok) ctx.bumpFileSystemEpoch()
        return ok
    }

    // ---- module management ----

    override fun availableModuleTypes(): List<UiModuleTypeOption> =
        ctx.services.moduleService.availableModuleTypes()

    override suspend fun createModule(
        name: String,
        typeId: String,
        languageLevel: String?,
        facetValues: Map<String, Map<String, Any?>>
    ): UiConfigResult = withContext(Dispatchers.IO) {
        ctx.services.moduleService.createModule(name, typeId, languageLevel, facetValues)
            .also { if (it.success) ctx.bumpFileSystemEpoch() }
    }

    override fun removeModule(name: String): Boolean =
        ctx.services.moduleService.removeModule(name).also { if (it) ctx.bumpFileSystemEpoch() }

    // ---- module configuration ----

    override fun configurableModules(): List<UiModuleRef> = ctx.services.moduleService.configurableModules()

    override suspend fun getModuleConfig(moduleName: String): UiModuleConfig? =
        withContext(Dispatchers.IO) { ctx.services.moduleService.getModuleConfig(moduleName) }

    override suspend fun updateModuleConfig(
        moduleName: String, edit: UiModuleConfigEdit
    ): UiConfigResult = withContext(Dispatchers.IO) {
        ctx.services.moduleService.updateModuleConfig(moduleName, edit)
            .also { if (it.success) ctx.bumpFileSystemEpoch() }
    }

    override suspend fun getBuildFeatures(moduleName: String): UiBuildFeatures? =
        withContext(Dispatchers.IO) { ctx.services.moduleService.getBuildFeatures(moduleName) }

    override suspend fun setBuildFeature(moduleName: String, feature: String, enabled: Boolean): UiConfigResult =
        withContext(Dispatchers.IO) {
            ctx.services.moduleService.setBuildFeature(moduleName, feature, enabled)
                .also { if (it.success) ctx.bumpFileSystemEpoch() }
        }

    override suspend fun missingProguardFiles(moduleName: String): List<UiMissingProguardFile> =
        withContext(Dispatchers.IO) { ctx.services.moduleService.missingProguardFiles(moduleName) }

    override suspend fun createProguardFile(moduleName: String, entry: String): String? =
        withContext(Dispatchers.IO) {
            ctx.services.moduleService.createProguardFile(moduleName, entry)?.toString()
                ?.also { ctx.bumpFileSystemEpoch() }
        }
}
