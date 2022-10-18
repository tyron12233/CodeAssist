package com.android.aaptcompiler

import com.android.ide.common.blame.SourceFilePosition
import com.android.ide.common.blame.SourcePosition
import com.android.utils.ILogger
import org.openjdk.javax.xml.stream.Location
import java.io.File

fun blameSource(
    source: Source,
    line: Int? = source.line,
    column: Int? = null
): BlameLogger.Source =
    BlameLogger.Source(source.path, line ?: -1, column ?: -1)

fun blameSource(
    source: Source,
    location: Location
): BlameLogger.Source =
    BlameLogger.Source(source.path, location.lineNumber, location.columnNumber)

open class BlameLogger(
    val logger: ILogger,
    private val userVisibleSourceTransform: (String) -> String,
    val blameMap: (Source) -> Source = { it }
) {

    constructor(logger: ILogger, blameMap: (Source) -> Source = { it })
            : this(logger, { it }, blameMap)

    data class Source(
            val sourcePath: String,
            val line: Int = -1,
            val column: Int = -1
    ) {

        override fun toString(): String {
            var result = sourcePath
            if (line != -1) {
                result += ":$line"
                if (column != -1) {
                    result += ":$column"
                }
            }
            return "$result: "
        }

        fun toSourceFilePosition() =
            SourceFilePosition(File(sourcePath), SourcePosition(
                    line,
                    column,
                    -1,
                    line,
                    column,
                    -1
            ))

        companion object {
            fun fromSourceFilePosition(filePosition: SourceFilePosition) =
                Source(filePosition.file.sourcePath!!,
                        filePosition.position.startLine,
                        filePosition.position.startColumn)
        }
    }

    fun error(message: String, source: Source? = null, throwable: Throwable? = null) {
        if (source != null) {
            logger.error(throwable, "${getOutputSource(source)}$message")
        } else {
            logger.error(throwable, message)
        }
    }

    fun warning(message: String, source: Source? = null) {
        if (source != null)
            logger.warning("${getOutputSource(source)}$message")
        else
            logger.warning(message)
    }

    fun info(message: String, source: Source? = null) {
        if (source != null)
            logger.info("${getOutputSource(source)}$message")
        else
            logger.info(message)
    }

    fun lifecycle(message: String, source: Source? = null) {
        if (source != null)
            logger.lifecycle("${getOutputSource(source)}$message")
        else
            logger.lifecycle(message)
    }

    fun quiet(message: String, source: Source? = null) {
        if (source != null)
            logger.quiet("${getOutputSource(source)}$message")
        else
            logger.quiet(message)
    }

    fun verbose(message: String, source: Source? = null) {
        if (source != null)
            logger.verbose("${getOutputSource(source)}$message")
        else
            logger.verbose(message)
    }

    fun getOutputSource(source: Source) : Source {
        return getOriginalSource(
                source.copy(sourcePath = userVisibleSourceTransform(source.sourcePath))
            )
    }

    fun getOriginalSource(source: Source): Source {
        return blameMap(source)
    }
}
