package dev.ide.core.services

import dev.ide.android.support.tools.KeystoreCertInfo
import dev.ide.android.support.tools.KeystoreCreateSpec
import dev.ide.android.support.tools.KeystoreCrypto
import dev.ide.android.support.tools.KeystoreEntry
import dev.ide.android.support.tools.KeystoreRegistry
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiKeystoreCert
import dev.ide.ui.backend.UiKeystoreResult
import dev.ide.ui.backend.UiKeystoreSpec
import dev.ide.ui.backend.UiKeystoreValidation
import java.nio.file.Paths

/**
 * The keystore-registry CRUD → UI mapping, independent of any open project. Shared by the WORKSPACE-scoped
 * engine [SigningService] (for an open project) and the [dev.ide.core.backend.SigningBackend], which drives
 * the same APPLICATION-scoped registry from the project picker's Settings & Tools hub with no project open.
 * The registry and its keystores are app-global, so none of these operations touch the project model — only
 * the per-build-type signing *assignment* (which lives in `module.toml`) needs an engine, and that stays on
 * [SigningService].
 */
internal object KeystoreRegistryOps {
    /** Every registered keystore, each with a best-effort summary of its key certificate. */
    fun keystores(reg: KeystoreRegistry): List<UiKeystore> = reg.all().map(::uiKeystore)

    fun createKeystore(reg: KeystoreRegistry, spec: UiKeystoreSpec): UiKeystoreResult {
        if (spec.commonName.isBlank()) return UiKeystoreResult(false, "A certificate name (CN) is required.")
        if (spec.storePass.length < 6) return UiKeystoreResult(false, "The keystore password must be at least 6 characters.")
        val r = reg.create(
            spec.name,
            KeystoreCreateSpec(
                storePass = spec.storePass, keyAlias = spec.keyAlias.ifBlank { "key0" },
                commonName = spec.commonName, organizationalUnit = spec.organizationalUnit,
                organization = spec.organization, locality = spec.locality, state = spec.state,
                country = spec.country, validityYears = spec.validityYears,
            ),
        )
        return r.fold(
            { UiKeystoreResult(true, "Created ${it.name}", it.id) },
            { UiKeystoreResult(false, it.message ?: "Keystore creation failed.") },
        )
    }

    fun importKeystore(
        reg: KeystoreRegistry, filePath: String, name: String, storePass: String, keyAlias: String, keyPass: String
    ): UiKeystoreResult {
        val r = reg.import(name, Paths.get(filePath), storePass, keyAlias, keyPass)
        return r.fold(
            { UiKeystoreResult(true, "Imported ${it.name}", it.id) },
            { UiKeystoreResult(false, it.message ?: "Keystore import failed.") },
        )
    }

    fun validateKeystore(filePath: String, storePass: String): UiKeystoreValidation {
        val v = KeystoreCrypto.validate(Paths.get(filePath), storePass)
        return UiKeystoreValidation(v.valid, v.aliases, v.certs.map(::uiCert), v.error)
    }

    fun deleteKeystore(reg: KeystoreRegistry, id: String): Boolean = reg.delete(id)

    fun uiKeystore(e: KeystoreEntry): UiKeystore {
        val cert = runCatching { KeystoreCrypto.inspect(Paths.get(e.file), e.storePass, e.keyAlias) }.getOrNull()
        return UiKeystore(
            id = e.id,
            name = e.name,
            fileName = Paths.get(e.file).fileName.toString(),
            keyAlias = e.keyAlias,
            certSubject = cert?.subject,
            sha256 = cert?.sha256,
            validUntilEpochMs = cert?.validUntilEpochMs,
        )
    }

    private fun uiCert(c: KeystoreCertInfo): UiKeystoreCert = UiKeystoreCert(
        c.alias, c.subject, c.issuer, c.validFromEpochMs, c.validUntilEpochMs, c.sha1, c.sha256,
    )
}
