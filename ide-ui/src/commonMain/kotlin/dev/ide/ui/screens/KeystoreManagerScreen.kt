package dev.ide.ui.screens

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
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

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
    onManageSigning: () -> Unit,
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

    Column(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack, boxSize = 38)
            Text("Keystore Manager", style = Ca.type.title3, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, modifier = Modifier.weight(1f))
        }
        status?.let {
            Text(it, style = Ca.type.footnote, color = if (statusError) Ca.colors.error else Ca.colors.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            KsCard {
                Text("Signing keystores", style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create or import the keystores you sign release builds with. They're stored once and shared " +
                        "across projects; assign one to a build type in a module's Signing tab.",
                    style = Ca.type.footnote, color = Ca.colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KsButton("Create", CaIcons.plus, accent = true) { onCreate() }
                    if (fileActions.canPickFile) {
                        KsButton("Import", CaIcons.download, accent = false) {
                            fileActions.pickFile { path -> if (path != null) onImport(path) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.control))
                        .clickable(remember { MutableInteractionSource() }, null, onClick = onManageSigning)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(CaIcons.layers, null, Modifier.size(15.dp), tint = Ca.colors.accent)
                    Text("Assign to a build (project signing)", style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, color = Ca.colors.accent)
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator(color = Ca.colors.accent) }
                keystores.isEmpty() -> Text(
                    "No keystores yet. Create or import one to sign release builds.",
                    style = Ca.type.footnote, color = Ca.colors.textTertiary, modifier = Modifier.padding(4.dp),
                )
                else -> keystores.forEach { ks ->
                    KeystoreCard(ks) {
                        if (backend.signing.deleteKeystore(ks.id)) {
                            status = "Deleted ${ks.name}"; statusError = false
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

    FormScaffold("New Keystore", onBack) {
        KsField("Name", name) { name = it }
        KsField("Key alias", alias) { alias = it }
        KsField("Password (≥ 6 chars; protects the store and key)", password, password = true) { password = it }
        KsField("Full name (CN)", cn) { cn = it }
        KsField("Organization (optional)", org) { org = it }
        KsField("Country code (optional)", country) { country = it }
        KsField("Validity (years)", validity, number = true) { validity = it.filter(Char::isDigit) }
        error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Ca.type.footnote, color = Ca.colors.error) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KsButton("Create keystore", CaIcons.check, accent = true, enabled = !busy) {
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
            KsButton("Cancel", null, accent = false, enabled = !busy, onClick = onBack)
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
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

    FormScaffold("Import Keystore", onBack) {
        Text(path, style = Ca.type.caption2, color = Ca.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        KsField("Name", name) { name = it }
        KsField("Keystore password", password, password = true) { password = it }
        KsField("Key alias (blank = first)", alias) { alias = it }
        KsField("Key password (blank = store password)", keyPass, password = true) { keyPass = it }
        error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Ca.type.footnote, color = Ca.colors.error) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KsButton("Import", CaIcons.check, accent = true, enabled = !busy) {
                error = null; busy = true
                scope.launch {
                    val r = backend.signing.importKeystore(path, name.trim(), password, alias.trim(), keyPass)
                    busy = false
                    if (r.success) onDone() else error = r.message
                }
            }
            KsButton("Cancel", null, accent = false, enabled = !busy, onClick = onBack)
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun KeystoreCard(ks: UiKeystore, onDelete: () -> Unit) {
    KsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CaIcons.key, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Column(Modifier.weight(1f)) {
                Text(ks.name, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${ks.fileName} · key: ${ks.keyAlias}", style = Ca.type.caption2, color = Ca.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButtonCa(CaIcons.close, "Delete ${ks.name}", onDelete, boxSize = 30, iconSize = 15, tint = Ca.colors.textTertiary)
        }
        val subject = ks.certSubject
        if (subject != null) {
            Spacer(Modifier.height(6.dp))
            Text(subject, style = Ca.type.caption2, color = Ca.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = buildList {
                ks.sha256?.let { add("SHA-256 " + it.replace(":", "").take(16) + "…") }
                ks.validUntilEpochMs?.let { add("expires ~" + approxYear(it)) }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString("   "), style = Ca.type.caption2, color = Ca.colors.textTertiary)
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Couldn't read the certificate (password may have changed).", style = Ca.type.caption2, color = Ca.colors.warning)
        }
    }
}

// ---- small building blocks ----

/** A full screen with a back/title header and a scrolling form body in a card. */
@Composable
private fun FormScaffold(title: String, onBack: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack, boxSize = 38)
            Text(title, style = Ca.type.title3, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            KsCard(content = body)
        }
    }
}

@Composable
private fun KsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun KsField(label: String, value: String, password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Ca.type.caption2, color = Ca.colors.textSecondary)
        Box(
            Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text),
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun KsButton(label: String, icon: ImageVector?, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (accent) Ca.colors.accent else Ca.colors.surface3
    val fg = if (accent) Ca.colors.textOnAccent else Ca.colors.textPrimary
    Row(
        Modifier.background(bg.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(Ca.radius.control))
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(15.dp), tint = fg) }
        Text(label, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** A rough calendar year from epoch-ms for a compact "expires ~YYYY" label (no java.time in commonMain). */
private fun approxYear(epochMs: Long): Int = 1970 + (epochMs / 31_556_952_000L).toInt()
