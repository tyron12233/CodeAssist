package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiKeystore
import dev.ide.ui.backend.UiKeystoreSpec
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.create
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.`import`
import dev.ide.ui.generated.resources.keystore_assign_to_build
import dev.ide.ui.generated.resources.keystore_cert_unreadable
import dev.ide.ui.generated.resources.keystore_country
import dev.ide.ui.generated.resources.keystore_create_button
import dev.ide.ui.generated.resources.keystore_create_title
import dev.ide.ui.generated.resources.keystore_deleted
import dev.ide.ui.generated.resources.keystore_empty
import dev.ide.ui.generated.resources.keystore_expires
import dev.ide.ui.generated.resources.keystore_full_name
import dev.ide.ui.generated.resources.keystore_import_alias
import dev.ide.ui.generated.resources.keystore_import_key_password
import dev.ide.ui.generated.resources.keystore_import_password
import dev.ide.ui.generated.resources.keystore_import_title
import dev.ide.ui.generated.resources.keystore_key_alias
import dev.ide.ui.generated.resources.keystore_manager_title
import dev.ide.ui.generated.resources.keystore_name
import dev.ide.ui.generated.resources.keystore_organization
import dev.ide.ui.generated.resources.keystore_password
import dev.ide.ui.generated.resources.keystore_row_subtitle
import dev.ide.ui.generated.resources.keystore_section_description
import dev.ide.ui.generated.resources.keystore_section_title
import dev.ide.ui.generated.resources.keystore_sha256
import dev.ide.ui.generated.resources.keystore_validity
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The global signing-keystore manager: list every registered keystore (with its key certificate summary),
 * with **Create** and **Import** opening their own screens, plus a shortcut to a module's Signing tab to
 * assign a keystore to a build. Keystores + secrets live in the app-home registry, shared across projects.
 */
@Composable
fun KeystoreManagerScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onImport: (path: String) -> Unit,
    /** Jump to a module's Signing tab to assign keystores to builds. Null when no project is open (the
     *  manager is reachable from the project picker, where assignment doesn't apply) — the row is hidden. */
    onManageSigning: (() -> Unit)? = null,
    fileActions: FileActions = FileActions.None,
) {
    val scope = rememberCoroutineScope()
    var keystores by remember { mutableStateOf<List<UiKeystore>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }

    // Reloads on every (re)entry — returning from the Create/Import screens remounts this and refreshes the list.
    LaunchedEffect(Unit) {
        loading = true
        keystores = runCatching { backend.signing.keystores() }.getOrDefault(emptyList())
        loading = false
    }

    ExpressiveScaffold(title = stringResource(Res.string.keystore_manager_title), onBack = onBack) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            KsCard {
                Text(stringResource(Res.string.keystore_section_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.keystore_section_description),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KsButton(stringResource(Res.string.create), CaIcons.plus, accent = true) { onCreate() }
                    if (fileActions.canPickFile) {
                        KsButton(stringResource(Res.string.`import`), CaIcons.download, accent = false) {
                            fileActions.pickFile { path -> if (path != null) onImport(path) }
                        }
                    }
                }
                if (onManageSigning != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.control))
                            .clickable(remember { MutableInteractionSource() }, null, onClick = onManageSigning)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(CaIcons.layers, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(Res.string.keystore_assign_to_build), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                keystores.isEmpty() -> Text(
                    stringResource(Res.string.keystore_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(4.dp),
                )
                else -> keystores.forEach { ks ->
                    val deletedMsg = stringResource(Res.string.keystore_deleted, ks.name)
                    KeystoreCard(ks) {
                        if (backend.signing.deleteKeystore(ks.id)) {
                            status = deletedMsg; statusError = false
                            scope.launch { keystores = runCatching { backend.signing.keystores() }.getOrDefault(emptyList()) }
                        }
                    }
                }
            }
        }
    }
}

/** Dedicated screen: generate a new keystore (keypair + self-signed cert). [onDone] returns to the manager. */
@Composable
fun KeystoreCreateScreen(backend: IdeBackend, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("release") }
    var alias by remember { mutableStateOf("key0") }
    var password by remember { mutableStateOf("") }
    var cn by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var validity by remember { mutableStateOf("25") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FormScaffold(stringResource(Res.string.keystore_create_title), onBack) {
        KsField(stringResource(Res.string.keystore_name), name) { name = it }
        KsField(stringResource(Res.string.keystore_key_alias), alias) { alias = it }
        KsField(stringResource(Res.string.keystore_password), password, password = true) { password = it }
        KsField(stringResource(Res.string.keystore_full_name), cn) { cn = it }
        KsField(stringResource(Res.string.keystore_organization), org) { org = it }
        KsField(stringResource(Res.string.keystore_country), country) { country = it }
        KsField(stringResource(Res.string.keystore_validity), validity, number = true) { validity = it.filter(Char::isDigit) }
        error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KsButton(stringResource(Res.string.keystore_create_button), CaIcons.check, accent = true, enabled = !busy) {
                error = null; busy = true
                scope.launch {
                    val r = backend.signing.createKeystore(
                        UiKeystoreSpec(
                            name = name.trim(), storePass = password, keyAlias = alias.trim().ifBlank { "key0" },
                            commonName = cn.trim(), organization = org.trim().ifBlank { null }, country = country.trim().ifBlank { null },
                            validityYears = validity.toIntOrNull()?.coerceIn(1, 1000) ?: 25,
                        ),
                    )
                    busy = false
                    if (r.success) onDone() else error = r.message
                }
            }
            KsButton(stringResource(Res.string.cancel), null, accent = false, enabled = !busy, onClick = onBack)
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        }
    }
}

/** Dedicated screen: register the keystore picked at [path] after verifying its password. */
@Composable
fun KeystoreImportScreen(backend: IdeBackend, path: String, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val baseName = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    var name by remember(path) { mutableStateOf(baseName) }
    var password by remember(path) { mutableStateOf("") }
    var alias by remember(path) { mutableStateOf("") }
    var keyPass by remember(path) { mutableStateOf("") }
    var busy by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }

    FormScaffold(stringResource(Res.string.keystore_import_title), onBack) {
        Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        KsField(stringResource(Res.string.keystore_name), name) { name = it }
        KsField(stringResource(Res.string.keystore_import_password), password, password = true) { password = it }
        KsField(stringResource(Res.string.keystore_import_alias), alias) { alias = it }
        KsField(stringResource(Res.string.keystore_import_key_password), keyPass, password = true) { keyPass = it }
        error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KsButton(stringResource(Res.string.`import`), CaIcons.check, accent = true, enabled = !busy) {
                error = null; busy = true
                scope.launch {
                    val r = backend.signing.importKeystore(path, name.trim(), password, alias.trim(), keyPass)
                    busy = false
                    if (r.success) onDone() else error = r.message
                }
            }
            KsButton(stringResource(Res.string.cancel), null, accent = false, enabled = !busy, onClick = onBack)
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun KeystoreCard(ks: UiKeystore, onDelete: () -> Unit) {
    KsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CaIcons.key, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(ks.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(Res.string.keystore_row_subtitle, ks.fileName, ks.keyAlias), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButtonCa(CaIcons.close, stringResource(Res.string.delete), onDelete, boxSize = 30, iconSize = 15, tint = MaterialTheme.colorScheme.outline)
        }
        val subject = ks.certSubject
        if (subject != null) {
            Spacer(Modifier.height(6.dp))
            Text(subject, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val shaLabel = ks.sha256?.let { stringResource(Res.string.keystore_sha256, it.replace(":", "").take(16) + "…") }
            val expiresLabel = ks.validUntilEpochMs?.let { stringResource(Res.string.keystore_expires, approxYear(it).toString()) }
            val parts = buildList {
                shaLabel?.let { add(it) }
                expiresLabel?.let { add(it) }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString("   "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        } else {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.keystore_cert_unreadable), style = MaterialTheme.typography.labelSmall, color = Ide.colors.warning)
        }
    }
}

// ---- small building blocks ----

/** A full screen with a back/title header and a scrolling form body in a card. */
@Composable
private fun FormScaffold(title: String, onBack: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    ExpressiveScaffold(title = title, onBack = onBack) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            KsCard(content = body)
        }
    }
}

@Composable
private fun KsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun KsField(label: String, value: String, password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun KsButton(label: String, icon: ImageVector?, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.background(bg.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(Ca.radius.control))
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(15.dp), tint = fg) }
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** A rough calendar year from epoch-ms for a compact "expires ~YYYY" label (no java.time in commonMain). */
private fun approxYear(epochMs: Long): Int = 1970 + (epochMs / 31_556_952_000L).toInt()
