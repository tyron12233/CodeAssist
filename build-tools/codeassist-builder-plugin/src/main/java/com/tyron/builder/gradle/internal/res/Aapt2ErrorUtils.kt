package com.tyron.builder.gradle.internal.res

import com.android.aaptcompiler.BlameLogger
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.SourceFilePosition
import com.android.ide.common.resources.CompileResourceRequest
import com.android.utils.StdLogger
import com.google.common.base.Charsets
import com.google.common.collect.ImmutableList
import com.tyron.builder.common.resources.relativeResourcePathToAbsolutePath
import com.tyron.builder.gradle.errors.MessageReceiverImpl
import com.tyron.builder.gradle.errors.humanReadableMessage
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.tasks.manifest.findOriginalManifestFilePosition
import com.tyron.builder.ide.common.blame.MergingLog
import com.tyron.builder.ide.common.blame.parser.ToolOutputParser
import com.tyron.builder.ide.common.blame.parser.aapt.Aapt2OutputParser
import com.tyron.builder.ide.common.blame.parser.aapt.AbstractAaptOutputParser
import com.tyron.builder.internal.aapt.v2.Aapt2Exception
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths


/**
 * Rewrite exceptions to point to their original files.
 *
 * Returns the same exception as is if it could not be rewritten.
 *
 * This is expensive, so should only be used if the build is going to fail anyway.
 * The merging log is used directly from memory, as this only is needed within the resource merger.
 */
fun rewriteCompileException(
    e: Aapt2Exception,
    request: CompileResourceRequest,
    errorFormatMode: SyncOptions.ErrorFormatMode,
    enableBlame: Boolean,
    logger: Logger
): Aapt2Exception {
    if (!enableBlame) {
        return rewriteException(e, errorFormatMode, false, logger) {
            it
        }
    }
    if (request.blameMap.isEmpty()) {
        if (request.mergeBlameFolder != null) {
            val mergingLog = MergingLog(request.mergeBlameFolder!!)
            return rewriteException(e, errorFormatMode, true, logger) {
                mergingLog.find(it)
            }
        }

        val originalException =
            if (request.inputFile == request.originalInputFile) {
                e
            } else {
                Aapt2Exception.create(
                    description = "Failed to compile android resource " +
                            "'${request.originalInputFile.absolutePath}'.",
                    cause = e,
                    output = e.output?.replace(
                        request.inputFile.absolutePath,
                        request.originalInputFile.absolutePath
                    ),
                    processName = e.processName,
                    command = e.command
                )
            }

        return rewriteException(originalException, errorFormatMode, false, logger) {
            it
        }
    }
    return rewriteException(e, errorFormatMode, true, logger) {
        if (it.file.sourceFile?.absolutePath == request.originalInputFile.absolutePath) {
            MergingLog.find(it.position, request.blameMap) ?: it
        } else {
            it
        }
    }
}

/**
 * Rewrite exceptions to point to their original files.
 *
 * Returns the same exception as is if it could not be rewritten.
 *
 * This is expensive, so should only be used if the build is going to fail anyway.
 * The merging log is loaded from files lazily.
 */
fun rewriteLinkException(
    e: Aapt2Exception,
    errorFormatMode: SyncOptions.ErrorFormatMode,
    mergeBlameFolder: File?,
    manifestMergeBlameFile: File?,
    identifiedSourceSetMap: Map<String, String>,
    logger: Logger,
): Aapt2Exception {
    if (mergeBlameFolder == null && manifestMergeBlameFile == null) {
        return rewriteException(e, errorFormatMode, false, logger) {
            it
        }
    }
    var mergingLog: MergingLog? = null
    if (mergeBlameFolder != null) {
        mergingLog = MergingLog(mergeBlameFolder, identifiedSourceSetMap)
    }

    var manifestMergeBlameContents: List<String>? = null
    if (manifestMergeBlameFile != null && manifestMergeBlameFile.isFile) {
        manifestMergeBlameContents = manifestMergeBlameFile.readLines(Charsets.UTF_8)
    }

    return rewriteException(e, errorFormatMode, true, logger, identifiedSourceSetMap) {
        var newFile = it
        if (mergingLog != null) {
            newFile = mergingLog.find(it)
        }
        // If the merging log fails to find the original position, then try the manifest merge blame
        if (it == newFile && manifestMergeBlameContents != null) {
            newFile = findOriginalManifestFilePosition(manifestMergeBlameContents, it)
        }
        newFile
    }
}

/**
 * Creates a blame logger for the given [CompileResourceRequest] to be passed into the
 * [compileResource].
 *
 * @param request The request being sent through [ResourceCompilerRunnable].
 *
 * @param logger: Logger the logger for the [BlameLogger] to be wrapped around.
 *
 * @return A Blame Logger that can rewrite sources, to their correct locations pre-merge.
 */
fun blameLoggerFor(
    request: CompileResourceRequest, logger: LoggerWrapper
): BlameLogger {
    val sourcePathFunc = if (request.identifiedSourceSetMap.any()) {
        relativeResourcePathToAbsolutePath(
            request.identifiedSourceSetMap,
            FileSystems.getDefault()
        )
    } else {
        { it }
    }
    if (request.blameMap.isEmpty()) {
        if (request.mergeBlameFolder != null) {
            val mergingLog = MergingLog(request.mergeBlameFolder!!)
            return BlameLogger(
                logger,
                sourcePathFunc,
            ) {
                val sourceFile = it.toSourceFilePosition()
                BlameLogger.Source.fromSourceFilePosition(mergingLog.find(sourceFile))
            }
        }
        return BlameLogger(
            logger,
            sourcePathFunc
        )
    }
    return BlameLogger(
        logger, sourcePathFunc
    ) {
        if (getPath(it.sourcePath)?.toAbsolutePath() ==
            request.originalInputFile.toPath().toAbsolutePath()
        ) {
            val sourceFile = it.toSourceFilePosition()
            val foundSource = MergingLog.find(sourceFile.position, request.blameMap)
            if (foundSource == null) {
                it
            } else {
                BlameLogger.Source.fromSourceFilePosition(foundSource)
            }
        } else {
            it
        }
    }
}

/** Attempt to rewrite the given exception using the lookup function. */
private fun rewriteException(
    e: Aapt2Exception,
    errorFormatMode: SyncOptions.ErrorFormatMode,
    rewriteFilePositions: Boolean,
    logger: Logger,
    identifiedSourceSetMap: Map<String, String> = emptyMap(),
    blameLookup: (SourceFilePosition) -> SourceFilePosition
): Aapt2Exception {
    try {
        var messages =
            ToolOutputParser(
                Aapt2OutputParser(identifiedSourceSetMap),
                Message.Kind.SIMPLE,
                StdLogger(StdLogger.Level.INFO)
            ).parseToolOutput(e.output ?: "", true)
        if (messages.isEmpty()) {
            // No messages were parsed, create a dummy message.
            messages = listOf(
                Message(
                    Message.Kind.ERROR,
                    e.output ?: "",
                    "",
                    //noinspection VisibleForTests
                    AbstractAaptOutputParser.AAPT_TOOL_NAME,
                    SourceFilePosition.UNKNOWN
                )
            )
        }

        if (rewriteFilePositions) {
            messages = messages.map { message ->
                message.copy(
                    sourceFilePositions = rewritePositions(
                        message.sourceFilePositions,
                        blameLookup
                    )
                )
            }
        }

        val detailedMessage = messages.joinToString("\n") {
            humanReadableMessage(it)
        }

        // Log messages in a json format so parsers can parse and show them in the build output
        // window.
        if (errorFormatMode == SyncOptions.ErrorFormatMode.MACHINE_PARSABLE) {
            MessageReceiverImpl(errorFormatMode, logger).run {
                messages.map { message ->
                    message.copy(
                        text = e.description,
                        rawMessage = humanReadableMessage(message)
                    )
                }.forEach(this::receiveMessage)
            }
        }

        return Aapt2Exception.create(
            description = e.description,
            cause = e.cause,
            output = detailedMessage,
            processName = e.processName,
            command = e.command
        )
    } catch (e2: Exception) {
        // Something went wrong, report the original error with the error reporting error suppressed
        return e.apply { addSuppressed(e2) }
    }
}

private fun rewritePositions(
    sourceFilePositions: List<SourceFilePosition>,
    blameLookup: (SourceFilePosition) -> SourceFilePosition
): ImmutableList<SourceFilePosition> =
    ImmutableList.builder<SourceFilePosition>().apply {
        sourceFilePositions.forEach { add(blameLookup.invoke(it)) }
    }.build()

/** Converts a path string to a `Path`.  */
fun getPath(path: String?): Path? {
    if (path == null) return null
    return Paths.get(path)
}