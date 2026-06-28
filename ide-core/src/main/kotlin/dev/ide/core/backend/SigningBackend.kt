package dev.ide.core.backend

import dev.ide.core.BackendContext
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
 * [SigningService] over the engine: the global keystore registry (create/import/validate/delete) plus
 * per-build-type signing assignment. Crypto + file I/O run on [Dispatchers.IO]; assigning a keystore mutates
 * `module.toml`, so it bumps the file-system epoch (like the other module-config mutations).
 */
internal class SigningBackend(private val ctx: BackendContext) : SigningService {

    override suspend fun keystores(): List<UiKeystore> =
        withContext(Dispatchers.IO) { ctx.services.keystores() }

    override suspend fun createKeystore(spec: UiKeystoreSpec): UiKeystoreResult =
        withContext(Dispatchers.IO) { ctx.services.createKeystore(spec) }

    override suspend fun importKeystore(filePath: String, name: String, storePass: String, keyAlias: String, keyPass: String): UiKeystoreResult =
        withContext(Dispatchers.IO) { ctx.services.importKeystore(filePath, name, storePass, keyAlias, keyPass) }

    override suspend fun validateKeystore(filePath: String, storePass: String): UiKeystoreValidation =
        withContext(Dispatchers.IO) { ctx.services.validateKeystore(filePath, storePass) }

    override fun deleteKeystore(id: String): Boolean = ctx.services.deleteKeystore(id)

    override fun signableModules(): List<String> = ctx.services.signableModules()

    override suspend fun signingAssignments(moduleName: String): UiSigningAssignments? =
        withContext(Dispatchers.IO) { ctx.services.signingAssignments(moduleName) }

    override suspend fun assignSigning(moduleName: String, buildType: String, keystoreId: String?): UiConfigResult =
        withContext(Dispatchers.IO) {
            ctx.services.assignSigning(moduleName, buildType, keystoreId).also { if (it.success) ctx.bumpFileSystemEpoch() }
        }
}
