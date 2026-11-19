package dev.ide.ui.backend.remote

import dev.ide.ui.backend.AnalysisPreempted
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.UiCaret
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.backend.UiSnippetStop
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.backend.UiTextRange

/**
 * The editor's language services across a byte channel: completion and diagnostics for the buffer being
 * typed in, answered by an engine on the far side.
 *
 * Every question carries the buffer's text, so the far side needs no memory of the file to answer it; the
 * live text of the other open files is told apart ([Client.updateDocument]). Both ends are here: [Client]
 * writes the questions and reads the answers, [Server] answers them from a real [EditorService].
 */
object RemoteEditor {
    /** "EDT1". The host's native side reads these four bytes to give editor calls a thread of their own. */
    const val MAGIC = 0x45445431
    private const val UPDATE_DOCUMENT = 1
    private const val COMPLETE = 2
    private const val COMPLETION_ACCEPTED = 3
    private const val ANALYZE = 4

    private const val OK = 0
    private const val FAILED = 1
    private const val PREEMPTED = 2

    /** Whether [request] is one of this protocol's. */
    fun handles(request: ByteArray): Boolean = request.size >= 8 && RemoteIn(request).int() == MAGIC

    /** The answer of a far side that cannot answer, which [Client] raises as an error with [message]. */
    fun failure(message: String): ByteArray = RemoteOut().apply { int(FAILED); string(message) }.done()

    /** The near side. Safe from several threads: it keeps nothing between calls. */
    class Client(private val channel: (ByteArray) -> ByteArray) {
        private fun ask(op: Int, write: RemoteOut.() -> Unit): RemoteIn {
            val reply = RemoteIn(channel(RemoteOut().apply { int(MAGIC); int(op); write() }.done()))
            if (reply.size < 4) error("The backend did not answer.")
            when (reply.int()) {
                OK -> return reply
                PREEMPTED -> throw AnalysisPreempted()
                else -> error(reply.string() ?: "The backend did not answer.")
            }
        }

        fun updateDocument(path: String, text: String) { ask(UPDATE_DOCUMENT) { string(path); string(text) } }

        fun complete(path: String, text: String, offset: Int): UiCompletionResult =
            ask(COMPLETE) { string(path); string(text); int(offset) }.run {
                val items = list {
                    UiCompletionItem(
                        label = string()!!, insertText = string()!!, detail = string(), container = string(), documentation = string(),
                        kind = UiCompletionKind.entries[int()], sortPriority = int(),
                        additionalEdits = list { UiTextEdit(int(), int(), string()!!) },
                        caret = if (bool()) UiCaret(int(), int()) else null,
                        snippet = if (bool()) {
                            val stops = list { UiSnippetStop(int(), list { UiTextRange(int(), int()) }, list { string()!! }) }
                            UiSnippet(stops, int())
                        } else null,
                    )
                }
                UiCompletionResult(items, int(), int(), bool())
            }

        fun completionAccepted(path: String, label: String) { ask(COMPLETION_ACCEPTED) { string(path); string(label) } }

        /** Throws [AnalysisPreempted] when the far side's analysis gave way to a completion. */
        fun analyze(path: String, text: String): List<UiDiagnostic> =
            ask(ANALYZE) { string(path); string(text) }.run {
                list { UiDiagnostic(UiSeverity.entries[int()], int(), int(), string()!!, int(), int(), bool()) }
            }
    }

    /** Answers [Client]'s questions from [editor]. A failure of any kind is an answer too. */
    class Server(private val editor: EditorService) {
        suspend fun handle(request: ByteArray): ByteArray = try {
            val input = RemoteIn(request)
            input.int()
            val out = RemoteOut().apply { int(OK) }
            when (val op = input.int()) {
                UPDATE_DOCUMENT -> editor.updateDocument(input.string()!!, input.string()!!)
                COMPLETE -> {
                    val result = editor.complete(input.string()!!, input.string()!!, input.int())
                    out.list(result.items) { item ->
                        out.string(item.label); out.string(item.insertText); out.string(item.detail); out.string(item.container)
                        out.string(item.documentation); out.int(item.kind.ordinal); out.int(item.sortPriority)
                        out.list(item.additionalEdits) { out.int(it.start); out.int(it.end); out.string(it.newText) }
                        out.bool(item.caret != null)
                        item.caret?.let { out.int(it.offset); out.int(it.selectionLength) }
                        out.bool(item.snippet != null)
                        item.snippet?.let { snippet ->
                            out.list(snippet.stops) { stop ->
                                out.int(stop.index)
                                out.list(stop.ranges) { out.int(it.start); out.int(it.end) }
                                out.list(stop.choices) { out.string(it) }
                            }
                            out.int(snippet.finalCaretOffset)
                        }
                    }
                    out.int(result.replaceStart); out.int(result.replaceEnd); out.bool(result.mayFilterLocally)
                }
                COMPLETION_ACCEPTED -> editor.completionAccepted(input.string()!!, input.string()!!)
                ANALYZE -> out.list(editor.analyze(input.string()!!, input.string()!!)) {
                    out.int(it.severity.ordinal); out.int(it.line); out.int(it.col); out.string(it.message)
                    out.int(it.startOffset); out.int(it.endOffset); out.bool(it.unused)
                }
                else -> error("unknown editor call $op")
            }
            out.done()
        } catch (preempted: AnalysisPreempted) {
            RemoteOut().apply { int(PREEMPTED) }.done()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            failure(t.message ?: t.toString())
        }
    }
}
