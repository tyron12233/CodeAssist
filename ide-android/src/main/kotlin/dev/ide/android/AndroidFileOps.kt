package dev.ide.android

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import dev.ide.core.CaprojFormat
import dev.ide.ui.backend.IdeBackend
import java.io.File

/**
 * The Android host's file/SAF/FileProvider plumbing, split out of [MainActivity]: content-URI import/copy,
 * Share and "Open with", "Save As" export, APK install, and "Open in Files". Pure Android glue over an
 * [activity]. The Compose layer owns the SAF launchers (they must be `remember`ed in composition) and the
 * pending-request state; it calls these to do the byte-level work once a URI comes back.
 */
internal class AndroidFileOps(private val activity: ComponentActivity) {

    /** Hand the APK at [path] to the system package installer (the OS install-confirmation UI). */
    fun promptInstall(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }.getOrElse {
        Toast.makeText(activity, "Couldn't open the installer for ${File(path).name}", Toast.LENGTH_SHORT).show()
    }

    /** Copy [uri]'s bytes into a new file under [targetDir]; returns the new path or null. */
    fun importUri(uri: Uri, targetDir: String, backend: IdeBackend): String? = runCatching {
        val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        backend.files.createFileBytes(targetDir, name, bytes)
    }.getOrNull()

    /** Copy an inbound (`Open with`) file into a uniquely-named cache path so each hand-off is a distinct
     *  path (the import preview re-opens on each new path). Returns the real path or null. */
    fun copyInboundToCache(uri: Uri, name: String): String? = runCatching {
        val dest = File(activity.cacheDir, "import-${System.currentTimeMillis()}-$name")
        activity.contentResolver.openInputStream(uri)
            ?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        dest.absolutePath
    }.getOrNull()

    /** Copy a picked content:// file into the app cache and return its real path (for keystore import). */
    fun copyUriToCache(uri: Uri): String? = runCatching {
        val name = queryDisplayName(uri) ?: "keystore-${System.currentTimeMillis()}"
        val dest = File(activity.cacheDir, "picked-$name")
        activity.contentResolver.openInputStream(uri)
            ?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        dest.absolutePath
    }.getOrNull()

    /** Share [path] to another app via a FileProvider content:// URI (no FileUriExposedException). */
    fun shareFile(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share ${File(path).name}"))
    }.getOrElse { Toast.makeText(activity, "Can't share this file", Toast.LENGTH_SHORT).show() }

    /** Copy [src]'s bytes into the user-chosen document [uri] (the "Save As"/export destination). */
    fun exportTo(uri: Uri, src: String) = runCatching {
        activity.contentResolver.openOutputStream(uri)
            ?.use { out -> File(src).inputStream().use { it.copyTo(out) } }
        Toast.makeText(activity, "Exported ${File(src).name}", Toast.LENGTH_SHORT).show()
    }.getOrElse { Toast.makeText(activity, "Couldn't export file", Toast.LENGTH_SHORT).show() }

    /**
     * Open the system Files app on CodeAssist's storage (served by `ProjectsDocumentsProvider`) so the user
     * can browse/manage there. Best-effort across OEM file managers: first tries to deep-link to [path]'s own
     * directory (a document-URI VIEW — DocumentsUI honors it; our doc ids are absolute paths), then the
     * provider root, then a generic Files launch, and finally explains where to look.
     */
    fun openInFiles(path: String?) {
        val auth = "${activity.packageName}.documents"
        val dirId = path?.let { p -> File(p).let { if (it.isDirectory) it else it.parentFile }?.absolutePath }
        val dirUri = dirId?.let { runCatching { DocumentsContract.buildDocumentUri(auth, it) }.getOrNull() }
        if (dirUri != null) {
            val viewDir = Intent(Intent.ACTION_VIEW)
                .setDataAndType(dirUri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (runCatching { activity.startActivity(viewDir); true }.getOrDefault(false)) return
        }
        val rootUri = DocumentsContract.buildRootUri(auth, AndroidIde.externalHome(activity).absolutePath)
        val viewRoot = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri, "vnd.android.document/root")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (runCatching { activity.startActivity(viewRoot); true }.getOrDefault(false)) return
        val browse = Intent("android.provider.action.BROWSE", rootUri)
        runCatching { activity.startActivity(browse) }.getOrElse {
            Toast.makeText(activity, "Open the Files app → CodeAssist to browse your projects", Toast.LENGTH_LONG).show()
        }
    }

    /** Open [url] in the device browser (the Beta "Submit suggestions" action). */
    fun openInBrowser(url: String) = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.getOrElse { Toast.makeText(activity, "No app to open links", Toast.LENGTH_SHORT).show() }

    fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** A best-effort content MIME type from the file extension (drives the share-sheet target list). */
    private fun mimeFor(path: String): String = when {
        path.endsWith(".apk") -> "application/vnd.android.package-archive"
        path.endsWith(".${CaprojFormat.EXTENSION}") -> CaprojFormat.MIME
        path.endsWith(".zip") || path.endsWith(".jar") -> "application/zip"
        path.endsWith(".txt") || path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".xml") -> "text/plain"
        else -> "application/octet-stream"
    }
}
