package dev.ide.ui.ext

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ui.concurrent.UiLock

/**
 * A kind of file a plugin adds to the file tree's **New ▸** menu: [title] on the row, [nameLabel] in the
 * dialog, and [files] for what a name turns into (relative path to text). [files] may throw
 * [IllegalArgumentException] to refuse a name, with a message for the user.
 */
class NewFileTemplateContribution(
    val id: String,
    val title: String,
    val nameLabel: String,
    val iconId: String?,
    val appliesTo: (dirPath: String) -> Boolean,
    val files: (dirPath: String, name: String) -> List<Pair<String, String>>,
)

/** A template the user picked for a directory, waiting for the name dialog to collect a name. */
class NewFileTemplateRequest(val dirPath: String, val dirLabel: String, val template: NewFileTemplateContribution)

/**
 * The New-file templates plugins registered, and the one request the name dialog is serving.
 *
 * The request is a slot rather than a callback threaded through the tree: the menu row is deep inside the
 * file navigator and the dialog belongs to the screen, and the two never need more than "this template, in
 * this directory".
 */
object NewFileTemplateRegistry {
    private val templates = ArrayList<NewFileTemplateContribution>()
    private val lock = UiLock()

    fun register(template: NewFileTemplateContribution): Registration {
        lock.withLock { templates.add(template) }
        return Registration { lock.withLock { templates.remove(template) } }
    }

    /** The templates to offer for [dirPath], in registration order. One whose [NewFileTemplateContribution.appliesTo] throws is left out. */
    fun forDirectory(dirPath: String): List<NewFileTemplateContribution> =
        lock.withLock { templates.toList() }.filter { runCatching { it.appliesTo(dirPath) }.getOrDefault(false) }

    var pending: NewFileTemplateRequest? by mutableStateOf(null)
}
