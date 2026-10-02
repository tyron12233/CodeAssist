package dev.ide.desktop

import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import java.awt.Desktop
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
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

    override val canPasteImage: Boolean = true

    override fun hasClipboardImage(): Boolean = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) ||
            (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) && copiedImageFile(clipboard) != null)
    }.getOrDefault(false)

    /**
     * A screenshot tool puts pixels on the clipboard; a file manager's Copy puts a file list. Both count as "an
     * image": pixels are written out as a PNG, a copied image file is handed back as it is.
     */
    override fun pasteImage(onPasted: (String?) -> Unit) {
        val path = runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            copiedImageFile(clipboard)?.let { return@runCatching it.absolutePath }
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return@runCatching null
            val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return@runCatching null
            val buffered = image as? BufferedImage ?: BufferedImage(
                image.getWidth(null).coerceAtLeast(1), image.getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB,
            ).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
            val out = File.createTempFile("pasted-", ".png").apply { deleteOnExit() }
            ImageIO.write(buffered, "png", out)
            out.absolutePath
        }.getOrNull()
        onPasted(path)
    }

    private fun copiedImageFile(clipboard: java.awt.datatransfer.Clipboard): File? {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return null
        val files = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*> ?: return null
        return files.filterIsInstance<File>().firstOrNull {
            it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
        }
    }

    override val canPickDirectory: Boolean = true

    override fun pickDirectory(onPicked: (String?) -> Unit) {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Choose a Gradle project folder"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
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
