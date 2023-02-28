package com.tyron.code.language

import com.tyron.completion.model.CachedCompletion
import com.tyron.completion.model.CompletionList
import com.tyron.legacyEditor.Editor
import java.io.File

class CachedAutoCompleteProvider(
    val editor: Editor,
    val provider: AbstractAutoCompleteProvider
    ) : AbstractAutoCompleteProvider() {

    var cachedCompletion: CachedCompletion? = null

    override fun getCompletionList(prefix: String?, line: Int, column: Int): CompletionList? {
        val newPrefix = getPrefix(editor, line, column)

        val incremental = isIncrementalCompletion(
            cachedCompletion,
            newPrefix,
            editor.currentFile,
            line, column
        )
        if (incremental) {
            val cachedList = cachedCompletion!!.completionList
            val copy = CompletionList.copy(cachedList, newPrefix)

            if (!copy.isIncomplete && copy.items.isNotEmpty()) {
                return copy
            }
        }

        val result = kotlin.runCatching {
            provider.getCompletionList(prefix, line, column)
        }

        cachedCompletion = if (result.isSuccess) {
            CachedCompletion(
                editor.currentFile,
                line,
                column,
                result.getOrThrow().prefix,
                result.getOrThrow()
            )
        } else {
            null
        }

        return result.getOrNull()
    }

    override fun getPrefix(editor: Editor?, line: Int, column: Int): String {
        return provider.getPrefix(editor, line, column)
    }

    private fun isIncrementalCompletion(
        cachedCompletion: CachedCompletion?,
        prefix: String,
        file: File,
        line: Int,
        column: Int
    ): Boolean {
        if (line == -1) {
            return false
        }
        if (column == -1) {
            return false
        }
        if (cachedCompletion == null) {
            return false
        }
        if (file != cachedCompletion.file) {
            return false
        }
        if (prefix.endsWith(".")) {
            return false
        }
        if (cachedCompletion.line != line) {
            return false
        }
        if (cachedCompletion.column > column) {
            return false
        }
        return if (!prefix.startsWith(cachedCompletion.prefix)) {
            false
        } else prefix.length - cachedCompletion.prefix.length ==
                column - cachedCompletion.column
    }
}