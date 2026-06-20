package dev.ide.ui.editor.core

/**
 * A backend-neutral view of the editor buffer for the platform IME's extracted-text mirror. Field-for-field it
 * mirrors Android's `ExtractedText` contract — selection offsets are RELATIVE to [startOffset]; a partial update
 * sets [partialStartOffset]/[partialEndOffset] to the replaced PRE-EDIT range (and [text] to the new content of
 * that range), while a full snapshot uses -1/-1 — but it lives in commonMain so the offset arithmetic, the part
 * that lands the caret on the wrong line when it's off, is unit-testable on the JVM without Android types. The
 * android IME bridge copies these fields straight into a real `ExtractedText`.
 */
class ExtractedTextSnapshot(
    val text: String,
    val startOffset: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val partialStartOffset: Int,
    val partialEndOffset: Int,
)

/**
 * A full extracted-text snapshot of the buffer for the initial/one-shot `getExtractedText` and the once-per-batch
 * refresh. Buffers larger than [maxChars] are windowed around the caret so a big file never risks
 * `TransactionTooLargeException` over the binder; the per-edit path uses [partialExtractedSnapshot] instead.
 */
fun EditorSession.extractedTextSnapshot(maxChars: Int): ExtractedTextSnapshot {
    val len = doc.length
    val sel = selection
    val start: Int
    val end: Int
    if (len <= maxChars) {
        start = 0
        end = len
    } else {
        start = (sel.min - maxChars / 2).coerceIn(0, len - maxChars)
        end = (start + maxChars).coerceAtMost(len)
    }
    return ExtractedTextSnapshot(
        text = doc.substring(start, end),
        startOffset = start,
        selectionStart = (sel.min - start).coerceIn(0, end - start),
        selectionEnd = (sel.max - start).coerceIn(0, end - start),
        partialStartOffset = -1,
        partialEndOffset = -1,
    )
}

/**
 * A partial extracted-text update for one contiguous [span]: the IME replaces the old document range
 * `[span.start, span.start + span.removed)` with the freshly inserted [EditSpan.added] chars, keeping its mirror
 * exact without a full resend. Call this AFTER the edit is applied — it reads the new buffer for the inserted text.
 */
fun EditorSession.partialExtractedSnapshot(span: EditSpan): ExtractedTextSnapshot {
    val sel = selection
    return ExtractedTextSnapshot(
        text = doc.substring(span.start, span.start + span.added), // new content of the changed range
        startOffset = span.start,
        selectionStart = sel.min - span.start, // relative to startOffset, per the framework contract
        selectionEnd = sel.max - span.start,
        partialStartOffset = span.start, // old range start
        partialEndOffset = span.start + span.removed, // old range end (pre-edit coordinates)
    )
}
