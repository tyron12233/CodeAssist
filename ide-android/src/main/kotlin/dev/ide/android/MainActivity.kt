package dev.ide.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        inbound.value = extractStream(intent)
        setContent {
            var backend by remember { mutableStateOf<IdeBackend?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { AndroidIde.bootstrap(applicationContext) } }
                    .onSuccess { s -> session = s; backend = s.backend }
                    .onFailure { e -> error = e.message ?: e.toString() }
            }

            // --- file import/share bridge (created once; no-ops until the backend is ready) ---
            var pendingTarget by remember { mutableStateOf<String?>(null) }
            var pendingCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val b = backend
                val target = pendingTarget
                val created = if (b != null && target != null) uris.mapNotNull { importUri(it, target, b) } else emptyList()
                pendingCallback?.invoke(created)
                pendingTarget = null; pendingCallback = null
            }
            val fileActions = remember {
                object : FileActions {
                    override val canImport: Boolean = true
                    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
                        pendingTarget = targetDir
                        pendingCallback = onImported
                        importLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*"))
                    }

                    override val canShare: Boolean = true
                    override fun share(path: String) = shareFile(path)

                    override val canOpenUrl: Boolean = true
                    override fun openUrl(url: String) = openInBrowser(url)

                    override val canReveal: Boolean = true
                    override fun reveal(path: String) = openProjectsInFiles()
                }
            }

            // Import an inbound "Open with" / shared file into the active project's first source root.
            LaunchedEffect(backend, inbound.value) {
                val b = backend
                val uri = inbound.value
                if (b != null && uri != null) {
                    val target = firstSourceRoot(b.fileTree())
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
                    composePreviewHost = (b as? dev.ide.core.IdeServicesBackend)?.let { dev.ide.android.AndroidComposePreviewHost(it) },
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

    /** Copy [uri]'s bytes into a new file under [targetDir]; returns the new path or null. */
    private fun importUri(uri: Uri, targetDir: String, backend: IdeBackend): String? = runCatching {
        val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        backend.createFileBytes(targetDir, name, bytes)
    }.getOrNull()

    /** Share [path] to another app via a FileProvider content:// URI (no FileUriExposedException). */
    private fun shareFile(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (path.endsWith(".zip")) "application/zip" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${File(path).name}"))
    }.getOrElse { Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show() }

    /**
     * Open the system Files app at CodeAssist's app directory (served by [ProjectsDocumentsProvider]) so
     * the user can browse/import there. Best-effort across OEM file managers: tries the DocumentsProvider
     * root, then a generic Files launch, and finally explains where to look. Doesn't deep-link to a
     * specific subfolder — file managers don't honor that uniformly — it opens the app root.
     */
    private fun openProjectsInFiles() {
        val rootId = AndroidIde.externalHome(this).absolutePath
        val rootUri = android.provider.DocumentsContract.buildRootUri("$packageName.documents", rootId)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(rootUri, "vnd.android.document/root")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (runCatching { startActivity(view); true }.getOrDefault(false)) return
        // Fall back to whatever handles the storage/Files browse action, else tell the user where to look.
        val browse = Intent("android.provider.action.BROWSE", rootUri)
        runCatching { startActivity(browse) }.getOrElse {
            Toast.makeText(this, "Open the Files app → CodeAssist to browse your projects", Toast.LENGTH_LONG).show()
        }
    }

    /** Open [url] in the device browser (the Beta "Submit suggestions" action). */
    private fun openInBrowser(url: String) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.getOrElse { Toast.makeText(this, "No app to open links", Toast.LENGTH_SHORT).show() }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
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
        Modifier.fillMaxSize().background(Color(0xFF0E0E12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color(0xFF8B7BF0))
            Text(message, color = Color(0xFFB9B9C6), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}
