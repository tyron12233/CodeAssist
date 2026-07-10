package dev.ide.desktop

import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop [FileActions] over Swing: import via a [JFileChooser] (copying the chosen files into the
 * target project dir through [IdeBackend.createFile]) and "share" by revealing a file's folder in the
 * system file manager. Compose Desktop's UI thread is the AWT EDT, so the chooser and the [onImported]
 * callback (which touches Compose state) both run safely on it.
 */
class DesktopFileActions(private val backend: IdeBackend) : FileActions {
    override val canImport: Boolean = true

    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                dialogTitle = "Import files into project"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val created = chooser.selectedFiles.mapNotNull { f ->
                    runCatching { backend.files.createFileBytes(targetDir, f.name, f.readBytes()) }.getOrNull()
                }
                onImported(created)
            } else {
                onImported(emptyList())
            }
        }
    }

    override val canPickFile: Boolean = true

    override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Choose a file"
                if (extensions.isNotEmpty()) {
                    fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { ".$it" }, *extensions.toTypedArray())
                }
            }
            val path = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
            onPicked(path)
        }
    }

    override val canShare: Boolean = true

    override fun share(path: String) {
        runCatching {
            val file = File(path)
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.parentFile ?: file)
        }
    }

    override val canExport: Boolean = true

    /** "Save As": pick a destination via [JFileChooser] and copy the file there (e.g. a built APK out of the project). */
    override fun exportFile(path: String) {
        SwingUtilities.invokeLater {
            val src = File(path)
            val chooser = JFileChooser().apply {
                dialogTitle = "Export ${src.name}"
                selectedFile = File(src.name)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.let { dest -> runCatching { src.copyTo(dest, overwrite = true) } }
            }
        }
    }

    override val canOpenUrl: Boolean = true

    override fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI(url))
        }
    }

    override val canReveal: Boolean = true

    /** Reveal [path] in the system file manager (the folder itself, or a file's parent). */
    override fun reveal(path: String) {
        runCatching {
            val file = File(path)
            val dir = if (file.isDirectory) file else file.parentFile ?: file
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir)
        }
    }
}
