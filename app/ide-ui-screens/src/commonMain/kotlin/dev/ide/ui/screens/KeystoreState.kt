package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiKeystoreSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** State and intents for the keystore list: what is registered, and deleting an entry. */
@Stable
internal class KeystoreManagerState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var keystores: List<UiKeystore> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(true)
        private set

    /** The name of the keystore deleted last, for the confirmation line (null once nothing was deleted). */
    var lastDeleted: String? by mutableStateOf(null)
        private set

    init {
        reload()
    }

    fun delete(keystore: UiKeystore) {
        if (!backend.signing.deleteKeystore(keystore.id)) return
        lastDeleted = keystore.name
        reload()
    }

    private fun reload() {
        scope.launch {
            loading = true
            keystores = runCatching { backend.signing.keystores() }.getOrDefault(emptyList())
            loading = false
        }
    }
}

@Composable
internal fun rememberKeystoreManagerState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): KeystoreManagerState = remember(backend, scope) { KeystoreManagerState(backend, scope) }

/** The create-keystore form: the fields, and generating the keypair plus its self-signed certificate. */
@Stable
internal class KeystoreCreateState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var name: String by mutableStateOf("release")
        private set
    var alias: String by mutableStateOf("key0")
        private set
    var password: String by mutableStateOf("")
        private set
    var commonName: String by mutableStateOf("")
        private set
    var organization: String by mutableStateOf("")
        private set
    var country: String by mutableStateOf("")
        private set
    var validity: String by mutableStateOf("25")
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    fun updateName(value: String) { name = value }

    fun updateAlias(value: String) { alias = value }

    fun updatePassword(value: String) { password = value }

    fun updateCommonName(value: String) { commonName = value }

    fun updateOrganization(value: String) { organization = value }

    fun updateCountry(value: String) { country = value }

    fun updateValidity(value: String) { validity = value.filter(Char::isDigit) }

    /** Generate the keystore; [onDone] returns to the manager when it lands. */
    fun create(onDone: () -> Unit) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            val result = backend.signing.createKeystore(
                UiKeystoreSpec(
                    name = name.trim(),
                    storePass = password,
                    keyAlias = alias.trim().ifBlank { "key0" },
                    commonName = commonName.trim(),
                    organization = organization.trim().ifBlank { null },
                    country = country.trim().ifBlank { null },
                    validityYears = validity.toIntOrNull()?.coerceIn(1, 1000) ?: 25,
                ),
            )
            busy = false
            if (result.success) onDone() else error = result.message
        }
    }
}

@Composable
internal fun rememberKeystoreCreateState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): KeystoreCreateState = remember(backend, scope) { KeystoreCreateState(backend, scope) }

/** The import-keystore form for the file at [path]: the credentials, and registering it once they verify. */
@Stable
internal class KeystoreImportState(
    private val backend: IdeBackend,
    private val path: String,
    private val scope: CoroutineScope,
) {
    var name: String by mutableStateOf(path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
        private set
    var password: String by mutableStateOf("")
        private set
    var alias: String by mutableStateOf("")
        private set
    var keyPassword: String by mutableStateOf("")
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    fun updateName(value: String) { name = value }

    fun updatePassword(value: String) { password = value }

    fun updateAlias(value: String) { alias = value }

    fun updateKeyPassword(value: String) { keyPassword = value }

    /** Register the keystore; [onDone] returns to the manager when it lands. */
    fun import(onDone: () -> Unit) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            val result = backend.signing.importKeystore(path, name.trim(), password, alias.trim(), keyPassword)
            busy = false
            if (result.success) onDone() else error = result.message
        }
    }
}

@Composable
internal fun rememberKeystoreImportState(
    backend: IdeBackend,
    path: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): KeystoreImportState = remember(backend, path, scope) { KeystoreImportState(backend, path, scope) }
