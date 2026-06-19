package dev.ide.android

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Surfaces CodeAssist's whole external app directory (`getExternalFilesDir(null)`) to the system Files app
 * and any SAF-aware file manager as a browsable, **read/write** root — without the All-Files-Access
 * permission. The exposed tree is the entire external files dir: the new app home (`codeassist/` with its
 * projects, the SDK `android.jar`, the debug keystore, the kotlinc home) AND sibling data from older app
 * versions — notably the v0.2.9 `Projects/` folder — so users can recover projects an update left behind.
 * This is the piece a plain `FileProvider` can't do: a
 * `FileProvider` only hands out one content:// URI at a time, whereas a `DocumentsProvider` lets another
 * app browse the whole tree and create/edit/delete/rename files in it (issues #1010 / #1016: dropping in
 * icons, layouts, music, and editing project files from a PC-style file manager).
 *
 * The [documentId] is simply a file's absolute path; every operation re-validates that the resolved file
 * stays inside the app home before touching it, so a crafted document id can't escape the sandbox.
 * The provider runs in the app process and resolves the same directory as [AndroidIde.externalHome], so
 * what the IDE writes is exactly what other apps see.
 */
class ProjectsDocumentsProvider : DocumentsProvider() {

    private val rootDir: File by lazy { AndroidIde.externalHome(context!!).apply { mkdirs() } }
    private val rootId: String get() = rootDir.absolutePath

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        rootDir.mkdirs()
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, rootId)
            add(Root.COLUMN_DOCUMENT_ID, rootId)
            add(Root.COLUMN_TITLE, "CodeAssist")
            add(Root.COLUMN_SUMMARY, "App files")
            // CREATE → other apps may add files here; IS_CHILD → the system can verify tree membership;
            // LOCAL_ONLY → on-device storage; SUPPORTS_SEARCH → the Files-app search box works.
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, rootDir.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addFileRow(cursor, resolve(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        parent.listFiles()
            // Full file view: dot-prefixed internals (.platform caches, .git) are shown too.
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { addFileRow(cursor, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = File(parentDocumentId).absolutePath + File.separator
        return File(documentId).absolutePath.startsWith(parent)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolve(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolve(parentDocumentId)
        val target = uniqueChild(parent, displayName)
        val ok = if (Document.MIME_TYPE_DIR == mimeType) target.mkdirs()
        else runCatching { target.parentFile?.mkdirs(); target.createNewFile() }.getOrDefault(false)
        if (!ok) throw FileNotFoundException("Failed to create $displayName in ${parent.name}")
        return target.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        if (!file.deleteRecursively()) throw FileNotFoundException("Failed to delete ${file.name}")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolve(documentId)
        require(documentId != rootId) { "Cannot rename the app root" }
        val target = File(file.parentFile, displayName)
        if (target.exists()) throw FileNotFoundException("${target.name} already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename ${file.name}")
        return target.absolutePath
    }

    override fun getDocumentType(documentId: String): String = mimeTypeOf(resolve(documentId))

    /** Resolve a [documentId] (an absolute path) to a [File], rejecting anything outside the app home. */
    private fun resolve(documentId: String): File {
        val file = File(documentId)
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val root = runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
        require(canonical == root || canonical.startsWith(root + File.separator)) {
            "Document is outside the app root: $documentId"
        }
        return file
    }

    private fun addFileRow(cursor: MatrixCursor, file: File) {
        val isDir = file.isDirectory
        var flags = 0
        if (isDir) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        // The root itself isn't deletable/renameable; everything beneath it is.
        if (file.absolutePath != rootId) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
            add(Document.COLUMN_DISPLAY_NAME, if (file.absolutePath == rootId) "CodeAssist" else file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** A non-colliding child of [parent] named [displayName] (`Main.java` → `Main (2).java`). */
    private fun uniqueChild(parent: File, displayName: String): File {
        var candidate = File(parent, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var n = 2
        while (candidate.exists()) { candidate = File(parent, "$base ($n)$ext"); n++ }
        return candidate
    }

    private companion object {
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
