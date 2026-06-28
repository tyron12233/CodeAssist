package dev.ide.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.ide.core.IdeServicesBackend
import dev.ide.ui.CodeAssistApp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android host. Bootstraps the real on-device engine ([AndroidIde]) off the main thread, then
 * renders the shared Compose IDE UI ([CodeAssistApp]) over the resulting [IdeBackend] — the same code
 * path the desktop runs. Provides the Android [FileActions]: SAF import (copy external files into the
 * active project) and FileProvider-backed Share/"Open with". A splash shows while the engine starts.
 */
class MainActivity : ComponentActivity() {

    private var session: AndroidIde.Session? = null

    /** A file handed in by another app ("Open with" / "Share to"), pending import once the engine is up. */
    private val inbound = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xfff)
        )

        super.onCreate(savedInstanceState)
        inbound.value = extractStream(intent)

        setContent {
            var backend by remember { mutableStateOf<IdeBackend?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { AndroidIde.bootstrap(applicationContext) } }.onSuccess { s ->
                        session = s; backend = s.backend
                    }.onFailure { e -> error = e.message ?: e.toString() }
            }

            var pendingTarget by remember { mutableStateOf<String?>(null) }
            var pendingCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val b = backend
                    val target = pendingTarget
                    val created = if (b != null && target != null) uris.mapNotNull {
                        importUri(
                            it, target, b
                        )
                    } else emptyList()
                    pendingCallback?.invoke(created)
                    pendingTarget = null; pendingCallback = null
                }
            var pendingPick by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val path = uri?.let { copyUriToCache(it) }
                    pendingPick?.invoke(path)
                    pendingPick = null
                }
            // "Save As" export: the user picks a destination (Files/Drive/Downloads); we copy the bytes there.
            var pendingExport by remember { mutableStateOf<String?>(null) }
            val exportLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    val src = pendingExport
                    pendingExport = null
                    if (uri != null && src != null) exportTo(uri, src)
                }
            val fileActions = remember {
                object : FileActions {
                    override val canImport: Boolean = true
                    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
                        pendingTarget = targetDir
                        pendingCallback = onImported
                        importLauncher.launch(
                            arrayOf(
                                "text/*", "application/json", "application/xml", "*/*"
                            )
                        )
                    }

                    override val canPickFile: Boolean = true
                    override fun pickFile(onPicked: (String?) -> Unit) {
                        pendingPick = onPicked
                        pickLauncher.launch(arrayOf("*/*"))
                    }

                    override val canShare: Boolean = true
                    override fun share(path: String) = shareFile(path)

                    override val canExport: Boolean = true
                    override fun exportFile(path: String) {
                        pendingExport = path
                        exportLauncher.launch(File(path).name)
                    }

                    override val canOpenUrl: Boolean = true
                    override fun openUrl(url: String) = openInBrowser(url)

                    override val canReveal: Boolean = true
                    override fun reveal(path: String) = openInFiles(path)

                    override val canInstallApk: Boolean = true
                    override fun installApk(path: String) = promptInstall(path)
                }
            }

            LaunchedEffect(backend, inbound.value) {
                val b = backend
                val uri = inbound.value
                if (b != null && uri != null) {
                    val target = firstSourceRoot(b.files.fileTree())
                    val path = if (target != null) importUri(uri, target, b) else null
                    Toast.makeText(
                        this@MainActivity,
                        if (path != null) "Imported ${File(path).name}" else "Couldn't import file",
                        Toast.LENGTH_SHORT,
                    ).show()
                    inbound.value = null
                }
            }

            val b = backend
            when {
                b != null -> CodeAssistApp(
                    b,
                    fileActions = fileActions,
                    // On-device Compose preview: render @Preview composables through the interpreter. The
                    // backend instance is stable across project switches (it swaps services internally), so
                    // one host suffices.
                    composePreviewHost = (b as? IdeServicesBackend)?.let {
                        AndroidComposePreviewHost(
                            it
                        )
                    },
                )

                error != null -> Splash("Failed to start: $error")
                else -> Splash("Starting CodeAssist…")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractStream(intent)?.let { inbound.value = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the *active* engine (a project switch may have swapped it), not just the initial one.
        session?.backend?.close()
    }

    /** Hand the APK at [path] to the system package installer (the OS install-confirmation UI). */
    private fun promptInstall(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }.getOrElse { Toast.makeText(this, "Couldn't open the installer for ${File(path).name}", Toast.LENGTH_SHORT).show() }

    /** Copy [uri]'s bytes into a new file under [targetDir]; returns the new path or null. */
    private fun importUri(uri: Uri, targetDir: String, backend: IdeBackend): String? = runCatching {
        val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        backend.files.createFileBytes(targetDir, name, bytes)
    }.getOrNull()

    /** Copy a picked content:// file into the app cache and return its real path (for keystore import). */
    private fun copyUriToCache(uri: Uri): String? = runCatching {
        val name = queryDisplayName(uri) ?: "keystore-${System.currentTimeMillis()}"
        val dest = File(cacheDir, "picked-$name")
        contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        dest.absolutePath
    }.getOrNull()

    /** Share [path] to another app via a FileProvider content:// URI (no FileUriExposedException). */
    private fun shareFile(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${File(path).name}"))
    }.getOrElse { Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show() }

    /** Copy [src]'s bytes into the user-chosen document [uri] (the "Save As"/export destination). */
    private fun exportTo(uri: Uri, src: String) = runCatching {
        contentResolver.openOutputStream(uri)?.use { out -> File(src).inputStream().use { it.copyTo(out) } }
        Toast.makeText(this, "Exported ${File(src).name}", Toast.LENGTH_SHORT).show()
    }.getOrElse { Toast.makeText(this, "Couldn't export file", Toast.LENGTH_SHORT).show() }

    /** A best-effort content MIME type from the file extension (drives the share-sheet target list). */
    private fun mimeFor(path: String): String = when {
        path.endsWith(".apk") -> "application/vnd.android.package-archive"
        path.endsWith(".zip") || path.endsWith(".jar") -> "application/zip"
        path.endsWith(".txt") || path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".xml") -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * Open the system Files app on CodeAssist's storage (served by [ProjectsDocumentsProvider]) so the user
     * can browse/manage there. Best-effort across OEM file managers: first tries to deep-link to [path]'s
     * own directory (a document-URI VIEW — DocumentsUI honors it; our doc ids are absolute paths), then the
     * provider root, then a generic Files launch, and finally explains where to look.
     */
    private fun openInFiles(path: String?) {
        val auth = "$packageName.documents"
        // Deep-link to the file's containing directory when we can (so "Open in Files" lands near the APK).
        val dirId = path?.let { p -> File(p).let { if (it.isDirectory) it else it.parentFile }?.absolutePath }
        val dirUri = dirId?.let { runCatching { DocumentsContract.buildDocumentUri(auth, it) }.getOrNull() }
        if (dirUri != null) {
            val viewDir = Intent(Intent.ACTION_VIEW)
                .setDataAndType(dirUri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (runCatching { startActivity(viewDir); true }.getOrDefault(false)) return
        }
        val rootUri = DocumentsContract.buildRootUri(auth, AndroidIde.externalHome(this).absolutePath)
        val viewRoot = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri, "vnd.android.document/root")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (runCatching { startActivity(viewRoot); true }.getOrDefault(false)) return
        // Fall back to whatever handles the storage/Files browse action, else tell the user where to look.
        val browse = Intent("android.provider.action.BROWSE", rootUri)
        runCatching { startActivity(browse) }.getOrElse {
            Toast.makeText(
                this, "Open the Files app → CodeAssist to browse your projects", Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Open [url] in the device browser (the Beta "Submit suggestions" action). */
    private fun openInBrowser(url: String) = runCatching {
        startActivity(
            Intent(
                Intent.ACTION_VIEW, Uri.parse(url)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.getOrElse { Toast.makeText(this, "No app to open links", Toast.LENGTH_SHORT).show() }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()

    private fun extractStream(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}

/** Depth-first search for the first source root (a node carrying a source-root path) in the tree. */
private fun firstSourceRoot(root: TreeNode): String? {
    root.sourceRootPath?.let { return it }
    for (child in root.children) firstSourceRoot(child)?.let { return it }
    return null
}

/** Minimal dark splash shown while the engine boots (before the themed UI is available). */
@Composable
private fun Splash(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFF8B7BF0))
            Text(
                message,
                color = Color(0xFFB9B9C6),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}
