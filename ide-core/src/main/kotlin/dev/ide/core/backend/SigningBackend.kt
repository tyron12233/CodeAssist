package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.services.KeystoreRegistryOps
import dev.ide.ui.backend.SigningService
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiKeystoreResult
import dev.ide.ui.backend.UiKeystoreSpec
import dev.ide.ui.backend.UiKeystoreValidation
import dev.ide.ui.backend.UiSigningAssignments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SigningService] split by scope:
 *  - **Keystore registry CRUD** (create/import/validate/delete/list) runs over the APPLICATION-scoped
 *    [BackendContext.keystoreRegistry] through the shared [KeystoreRegistryOps], so it works from the project
 *    picker's Settings & Tools hub with no project open. Crypto + file I/O run on [Dispatchers.IO].
 *  - **Signing assignment** (assign a keystore to a build type) mutates `module.toml`, so it needs an open
 *    project — it routes through the active engine and bumps the file-system epoch. With no project open the
 *    assignable-module surfaces are empty and assignment reports it instead of throwing.
 */
internal class SigningBackend(private val ctx: BackendContext) : SigningService {

    override suspend fun keystores(): List<UiKeystore> = withContext(Dispatchers.IO) {
        ctx.keystoreRegistry?.let { KeystoreRegistryOps.keystores(it) } ?: emptyList()
    }

    override suspend fun createKeystore(spec: UiKeystoreSpec): UiKeystoreResult = withContext(Dispatchers.IO) {
        ctx.keystoreRegistry?.let { KeystoreRegistryOps.createKeystore(it, spec) }
            ?: UiKeystoreResult(false, "No keystore registry available.")
    }

    override suspend fun importKeystore(filePath: String, name: String, storePass: String, keyAlias: String, keyPass: String): UiKeystoreResult =
        withContext(Dispatchers.IO) {
            ctx.keystoreRegistry?.let { KeystoreRegistryOps.importKeystore(it, filePath, name, storePass, keyAlias, keyPass) }
                ?: UiKeystoreResult(false, "No keystore registry available.")
        }

    override suspend fun validateKeystore(filePath: String, storePass: String): UiKeystoreValidation =
        withContext(Dispatchers.IO) { KeystoreRegistryOps.validateKeystore(filePath, storePass) }

    override fun deleteKeystore(id: String): Boolean = ctx.keystoreRegistry?.let { KeystoreRegistryOps.deleteKeystore(it, id) } ?: false

    override fun signableModules(): List<String> = ctx.servicesOrNull?.signing?.signableModules() ?: emptyList()

    override suspend fun signingAssignments(moduleName: String): UiSigningAssignments? =
        withContext(Dispatchers.IO) { ctx.servicesOrNull?.signing?.signingAssignments(moduleName) }

    override suspend fun assignSigning(moduleName: String, buildType: String, keystoreId: String?): UiConfigResult =
        withContext(Dispatchers.IO) {
            val signing = ctx.servicesOrNull?.signing
                ?: return@withContext UiConfigResult(false, "Open a project to assign signing.")
            signing.assignSigning(moduleName, buildType, keystoreId).also { if (it.success) ctx.bumpFileSystemEpoch() }
        }
}
