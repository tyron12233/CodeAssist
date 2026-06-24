package dev.ide.ui.editor.core

/**
 * A backend-neutral view of the editor buffer for the platform IME's extracted-text mirror. Field-for-field it
 * mirrors Android's `ExtractedText` contract. For a FULL snapshot [selectionStart]/[selectionEnd] are relative to
 * [startOffset] (a huge buffer is windowed around the caret) and [partialStartOffset]/[partialEndOffset] are -1.
 * For a PARTIAL update [partialStartOffset]/[partialEndOffset] are the replaced PRE-EDIT range and [text] is that
 * range's new content. The framework splices a partial update into its whole-document mirror by ABSOLUTE offset
 * and lands the caret at [selectionStart]/[selectionEnd] taken as absolute offsets too — it does not re-base them
 * by [startOffset] — so a partial update carries plain document offsets (with [startOffset] 0). Living in
 * commonMain keeps this offset arithmetic — the part that lands the IME's caret on the wrong line when it's off —
 * unit-testable on the JVM without Android types; the android IME bridge copies these fields into a real
 * `ExtractedText`.
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
 *
 * Every offset is an absolute document offset: the framework indexes its whole-document mirror with
 * [partialStartOffset]/[partialEndOffset] directly and places the caret at [selectionStart]/[selectionEnd]
 * directly, without subtracting [startOffset]. Valid only while that mirror spans the whole document from 0,
 * which the platform bridge enforces by only taking this path for an un-windowed buffer.
 */
fun EditorSession.partialExtractedSnapshot(span: EditSpan): ExtractedTextSnapshot {
    val sel = selection
    return ExtractedTextSnapshot(
        text = doc.substring(span.start, span.start + span.added), // new content of the changed range
        startOffset = 0,
        selectionStart = sel.min,
        selectionEnd = sel.max,
        partialStartOffset = span.start, // old range start
        partialEndOffset = span.start + span.removed, // old range end (pre-edit coordinates)
    )
}
