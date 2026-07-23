package dev.ide.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ide.core.CaprojFormat
import dev.ide.core.IdeServicesBackend
import dev.ide.ui.CodeAssistApp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android host. Bootstraps the real on-device engine ([AndroidIde]) off the main thread, then renders the
 * shared Compose IDE UI ([CodeAssistApp]) over the resulting [IdeBackend] — the same path the desktop runs.
 * The SAF/FileProvider plumbing lives in [AndroidFileOps]; the small host helpers (splash, "Save As" contract,
 * ad init, source-root search) in AndroidHostUi. This activity is just lifecycle + the Compose host + the SAF
 * launchers (which must be remembered in composition). A splash shows while the engine starts.
 */
class MainActivity : ComponentActivity() {

    private var session: AndroidIde.Session? = null

    /** A file handed in by another app ("Open with" / "Share to"), pending import once the engine is up. */
    private val inbound = mutableStateOf<Uri?>(null)

    /** Android file/SAF/FileProvider plumbing (byte-level import / share / export / install / reveal). */
    private val fileOps by lazy { AndroidFileOps(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0xfff))
        super.onCreate(savedInstanceState)
        inbound.value = extractStream(intent)
        initAds(this)

        setContent {
            var backend by remember { mutableStateOf<IdeBackend?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { AndroidIde.bootstrap(applicationContext) } }.onSuccess { s ->
                    session = s; backend = s.backend
                }.onFailure { e -> error = e.stackTraceToString() }
            }

            // POST_NOTIFICATIONS (Android 13+/API 33) is asked for at the FIRST build, not here at launch, so the
            // request lands in context — see BuildNotificationGate (:ide-ui). Nothing to request in this activity.
            var pendingTarget by remember { mutableStateOf<String?>(null) }
            var pendingCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val b = backend
                    val target = pendingTarget
                    val created =
                        if (b != null && target != null) uris.mapNotNull { fileOps.importUri(it, target, b) } else emptyList()
                    pendingCallback?.invoke(created)
                    pendingTarget = null; pendingCallback = null
                }
            var pendingPick by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    pendingPick?.invoke(uri?.let { fileOps.copyUriToCache(it) })
                    pendingPick = null
                }
            // "Save As" export: the user picks a destination (Files/Drive/Downloads); we copy the bytes there.
            var pendingExport by remember { mutableStateOf<String?>(null) }
            val exportLauncher =
                rememberLauncherForActivityResult(ExportDocumentContract()) { uri ->
                    val src = pendingExport
                    pendingExport = null
                    if (uri != null && src != null) fileOps.exportTo(uri, src)
                }
            val fileActions = remember {
                object : FileActions {
                    override val canImport: Boolean = true
                    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
                        pendingTarget = targetDir
                        pendingCallback = onImported
                        importLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*"))
                    }

                    override val canPickFile: Boolean = true
                    override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) {
                        pendingPick = onPicked
                        // Custom extensions (e.g. .caproj) have no registered MIME, so fall back to */* and let
                        // the caller validate the picked file; known extensions narrow the SAF picker.
                        val mimes = extensions.mapNotNull { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                        pickLauncher.launch(if (mimes.isEmpty()) arrayOf("*/*") else mimes.toTypedArray())
                    }

                    override val canShare: Boolean = true
                    override fun share(path: String) = fileOps.shareFile(path)

                    override val canExport: Boolean = true
                    override fun exportFile(path: String) {
                        pendingExport = path
                        exportLauncher.launch(File(path).name)
                    }

                    override val canOpenUrl: Boolean = true
                    override fun openUrl(url: String) = fileOps.openInBrowser(url)

                    override val canReveal: Boolean = true
                    override fun reveal(path: String) = fileOps.openInFiles(path)

                    override val canInstallApk: Boolean = true
                    override fun installApk(path: String) = fileOps.promptInstall(path)
                }
            }

            // Android advertising bridge (native ads + "remove ads" flow). Phase 1 = house ads + donation link.
            val adHost = remember { AndroidAdHost { url -> fileOps.openInBrowser(url) } }

            // A `.caproj` package handed in via "Open with" opens the import preview (see the branch below); any
            // other inbound file is copied into the open project's first source root as before.
            var importPackagePath by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(backend, inbound.value) {
                val b = backend
                val uri = inbound.value
                if (b != null && uri != null) {
                    val name = fileOps.queryDisplayName(uri) ?: ""
                    if (name.endsWith(".${CaprojFormat.EXTENSION}", ignoreCase = true)) {
                        val path = withContext(Dispatchers.IO) { fileOps.copyInboundToCache(uri, name) }
                        if (path != null) importPackagePath = path
                        else Toast.makeText(this@MainActivity, "Couldn't open the project package", Toast.LENGTH_SHORT).show()
                    } else {
                        val target = firstSourceRoot(b.files.fileTree())
                        val path = if (target != null) fileOps.importUri(uri, target, b) else null
                        Toast.makeText(
                            this@MainActivity,
                            if (path != null) "Imported ${File(path).name}" else "Couldn't import file",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    inbound.value = null
                }
            }

            val b = backend
            when {
                b != null -> CodeAssistApp(
                    b,
                    fileActions = fileActions,
                    adHost = adHost,
                    // On-device Compose preview: render @Preview composables through the interpreter. The backend
                    // instance is stable across project switches (it swaps services internally), so one host suffices.
                    composePreviewHost = (b as? IdeServicesBackend)?.let { AndroidComposePreviewHost(it) },
                    importPackagePath = importPackagePath,
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

    private fun extractStream(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}
