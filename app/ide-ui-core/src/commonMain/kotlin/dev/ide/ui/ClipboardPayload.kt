package dev.ide.ui

// Android delivers clipboard data over a Binder transaction whose buffer (~1 MB, shared process-wide) a long
// build log overflows, throwing TransactionTooLargeException and taking down the app. Cap the copied text well
// under that, keeping the TAIL (a build's errors and final status live at the end of the log) and noting the
// drop. Not localized: this is diagnostic clipboard payload, like the log lines themselves, not UI chrome.
private const val MAX_CLIPBOARD_CHARS = 200_000

/**
 * Every copy-to-clipboard path in the IDE goes through this: the build console, the run and logs screens, the
 * code editor's Copy/Cut, and the Android text-input actual. It lived in the build console for years because
 * that is where a megabyte of text first showed up, but it is a string function about a platform limit, not a
 * component -- and the editor's Android input needs it from a module that cannot depend on components.
 */
fun clipForClipboard(text: String): String {
    if (text.length <= MAX_CLIPBOARD_CHARS) return text
    val dropped = text.length - MAX_CLIPBOARD_CHARS
    return "[... $dropped earlier characters truncated to fit the clipboard ...]\n" +
        text.substring(text.length - MAX_CLIPBOARD_CHARS)
}
