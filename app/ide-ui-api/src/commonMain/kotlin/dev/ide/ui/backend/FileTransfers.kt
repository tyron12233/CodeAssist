package dev.ide.ui.backend

/** Whether a [FileService.transferPaths] call moves its items or copies them. */
enum class UiTransferKind { Move, Copy }

/** What a transfer does with an item whose name is already taken in the destination folder. */
enum class UiConflictChoice {
    /** Put the existing item aside (recoverably, where the backend can) and take its place. */
    Replace,

    /** Land under a free name next to the existing item ([keepBothName]). */
    KeepBoth,

    /** Leave the existing item alone and do not transfer this one. */
    Skip,
}

/** One item of a transfer: its absolute [path] and what to do if its name is taken in the destination. */
data class UiTransferItem(val path: String, val onConflict: UiConflictChoice = UiConflictChoice.Skip)

/**
 * The outcome of a [FileService.transferPaths] call. [done] maps each source path to where it landed. [skipped]
 * are sources left where they were (a conflict resolved as Skip, or a move into the folder it is already in).
 * [failed] maps a source to why it could not be transferred. [undoToken] reverses the whole call through
 * [FileService.undoFileOperation]; null when nothing changed or the backend cannot undo.
 */
data class UiTransferResult(
    val done: Map<String, String> = emptyMap(),
    val skipped: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
    val undoToken: String? = null,
)

/** The outcome of a [FileService.deletePaths] call; [undoToken] restores what was deleted (null when it can't). */
data class UiDeleteResult(
    val deleted: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
    val undoToken: String? = null,
)

/**
 * The outcome of [FileService.undoFileOperation]. [moved] maps where an item was to where the undo put it back
 * (so open tabs can follow), [removed] are paths the undo took away (copies it deleted), [restored] are paths it
 * brought back. [message] explains a failure, or names what could not be undone.
 */
data class UiUndoResult(
    val ok: Boolean,
    val message: String = "",
    val moved: Map<String, String> = emptyMap(),
    val removed: List<String> = emptyList(),
    val restored: List<String> = emptyList(),
)

/** The leaf (file or folder name) of [path], for either separator. */
fun pathLeafName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val SOURCE_EXTENSIONS = setOf("java", "kt")

/**
 * A free name for a copy of [name] when [taken] says the name exists, keeping it usable where it lands: a Java or
 * Kotlin source gets a type-name suffix (`Foo.java` -> `FooCopy.java`, then `FooCopy2.java`), any other
 * identifier-like name an underscore one that Android resources and packages accept (`main.xml` ->
 * `main_copy.xml`, folder `util` -> `util_copy`), and anything else a spaced one (`My notes.txt` ->
 * `My notes copy.txt`). The extension is kept for a file; a dotfile like `.gitignore` has none to keep.
 */
fun keepBothName(name: String, isDirectory: Boolean, taken: (String) -> Boolean): String {
    val dot = if (isDirectory) -1 else name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    val source = !isDirectory && ext.removePrefix(".") in SOURCE_EXTENSIONS && IDENTIFIER.matches(stem)
    val suffix = when {
        source -> "Copy"
        IDENTIFIER.matches(stem) -> "_copy"
        else -> " copy"
    }
    val spaced = suffix.startsWith(" ")
    var n = 1
    while (true) {
        val candidate = stem + suffix + (if (n == 1) "" else if (spaced) " $n" else "$n") + ext
        if (!taken(candidate)) return candidate
        n++
    }
}
