package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.core.EngineContext
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiKeystoreResult
import dev.ide.ui.backend.UiKeystoreSpec
import dev.ide.ui.backend.UiKeystoreValidation
import dev.ide.ui.backend.UiSigningAssignment
import dev.ide.ui.backend.UiSigningAssignments

/**
 * WORKSPACE-scoped engine service: the app-global keystore registry (create/import/validate/delete) plus
 * per-build-type signing assignment. Carved out of [dev.ide.core.IdeServices] into the platform service
 * container; the [KeystoreRegistry][dev.ide.android.support.tools.KeystoreRegistry] itself stays shared
 * (APPLICATION-scoped) infrastructure (the Android build's signing resolver + the picker's hub read it too)
 * and is reached through [EngineContext]. The registry CRUD → UI mapping is shared with the
 * [dev.ide.core.backend.SigningBackend] via [KeystoreRegistryOps]; only the signing *assignment* (which
 * mutates `module.toml`) needs this engine. The adapter adds only threading + the file-system epoch bump.
 */
internal class SigningService(private val ctx: EngineContext) {

    /** Every registered keystore, each with a best-effort summary of its key certificate. */
    fun keystores(): List<UiKeystore> = KeystoreRegistryOps.keystores(ctx.keystoreRegistry)

    fun createKeystore(spec: UiKeystoreSpec): UiKeystoreResult =
        KeystoreRegistryOps.createKeystore(ctx.keystoreRegistry, spec)

    fun importKeystore(
        filePath: String, name: String, storePass: String, keyAlias: String, keyPass: String
    ): UiKeystoreResult = KeystoreRegistryOps.importKeystore(ctx.keystoreRegistry, filePath, name, storePass, keyAlias, keyPass)

    fun validateKeystore(filePath: String, storePass: String): UiKeystoreValidation =
        KeystoreRegistryOps.validateKeystore(filePath, storePass)

    fun deleteKeystore(id: String): Boolean = KeystoreRegistryOps.deleteKeystore(ctx.keystoreRegistry, id)

    /** Modules that produce a signed APK (android-app) — the ones whose signing assignment matters. */
    fun signableModules(): List<String> =
        ctx.modules().filter { it.type.id == "android-app" }.map { it.name }

    /** Per-build-type signing assignments for [moduleName] + the assignable keystores; null for a non-Android module. */
    fun signingAssignments(moduleName: String): UiSigningAssignments? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val facet = module.facets.get(AndroidFacet.KEY) ?: return null
        val keystores = keystores()
        val byId = keystores.associateBy { it.id }
        val assignments = facet.buildTypes.map { bt ->
            UiSigningAssignment(bt.name, bt.signingConfig, bt.signingConfig?.let { byId[it]?.name })
        }
        return UiSigningAssignments(moduleName, keystores, assignments)
    }

    /** Set (or clear, when [keystoreId] is null) the keystore that signs [moduleName]'s [buildType]. */
    fun assignSigning(moduleName: String, buildType: String, keystoreId: String?): UiConfigResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiConfigResult(
            false,
            "No module '$moduleName'."
        )
        val facet = module.facets.get(AndroidFacet.KEY) ?: return UiConfigResult(
            false,
            "'$moduleName' is not an Android module."
        )
        val project =
            ctx.projectOf(module) ?: return UiConfigResult(false, "No project owns '$moduleName'.")
        if (keystoreId != null && ctx.keystoreRegistry.get(keystoreId) == null) {
            return UiConfigResult(false, "Unknown keystore '$keystoreId'.")
        }
        if (facet.buildTypes.none { it.name == buildType }) return UiConfigResult(
            false, "No build type '$buildType'."
        )
        val newTypes =
            facet.buildTypes.map { if (it.name == buildType) it.copy(signingConfig = keystoreId) else it }
        if (newTypes == facet.buildTypes) return UiConfigResult(true, "No change.")
        try {
            project.beginModification().apply {
                module(module.id).putFacet(facet.copy(buildTypes = newTypes))
                commit()
            }
        } catch (e: Exception) {
            return UiConfigResult(false, "Update failed: ${e.message}")
        }
        ctx.store.save()
        val target = keystoreId?.let { ctx.keystoreRegistry.get(it)?.name } ?: "debug (default)"
        return UiConfigResult(true, "$buildType → $target")
    }
}
