package com.tyron.builder.common.blame

import com.google.common.collect.ImmutableList
import javax.annotation.concurrent.Immutable

@Immutable
data class Message(
    val kind: Kind,
    val text: String,
    /** A list of source positions. Will always contain at least one item.
     * Must have type 'List' to satisfy KotlinBinaryCompatibilityTest*/
    val sourceFilePositions: List<SourceFilePosition> = ImmutableList.of(SourceFilePosition.UNKNOWN),
    val rawMessage: String = text,
    val toolName: String? = null
) {

    init {
        if (sourceFilePositions.isEmpty()) {
            throw IllegalArgumentException("Source file positions cannot be empty.")
        }
    }

    val sourcePath: String?
        get() {
            val file = sourceFilePositions[0].file.sourceFile ?: return null
            return file.absolutePath
        }

    /**
     * Returns a legacy 1-based line number.
     */
    @Deprecated(
        "Use sourceFilePositions",
        ReplaceWith("sourceFilePositions[0].position.startLine + 1")
    )
    val lineNumber: Int
        get() = sourceFilePositions[0].position.startLine + 1

    /**
     * @return a legacy 1-based column number.
     */
    @Deprecated(
        "Use sourceFilePositions",
        ReplaceWith("sourceFilePositions[0].position.startColumn + 1")
    )
    val column: Int
        get() = sourceFilePositions[0].position.startColumn + 1

    /**
     * Create a new message, which has a [Kind], a String which will be shown to the user and
     * at least one [SourceFilePosition].
     *
     * @param kind the message type.
     * @param text the text of the message.
     * @param sourceFilePosition the first source file position the message .
     * @param sourceFilePositions any additional source file positions, may be empty.
     */
    constructor(
        kind: Kind,
        text: String,
        sourceFilePosition: SourceFilePosition,
        vararg sourceFilePositions: SourceFilePosition
    ) : this(
        kind = kind,
        text = text,
        rawMessage = text,
        sourceFilePositions = ImmutableList.builder<SourceFilePosition>()
            .add(sourceFilePosition).add(*sourceFilePositions).build()
    )

    /**
     * Create a new message, which has a [Kind], a String which will be shown to the user and
     * at least one [SourceFilePosition].
     *
     * It also has a rawMessage, to store the original string for cases when the message is
     * constructed by parsing the output from another tool.
     *
     * @param kind the message kind.
     * @param text a human-readable string explaining the issue.
     * @param rawMessage the original text of the message, usually from an external tool.
     * @param toolName the name of the tool that produced the message, e.g. AAPT.
     * @param sourceFilePosition the first source file position.
     * @param sourceFilePositions any additional source file positions, may be empty.
     */
    constructor(
        kind: Kind,
        text: String,
        rawMessage: String,
        toolName: String?,
        sourceFilePosition: SourceFilePosition,
        vararg sourceFilePositions: SourceFilePosition
    ) : this(
        kind = kind,
        text = text,
        rawMessage = rawMessage,
        toolName = toolName,
        sourceFilePositions = ImmutableList.builder<SourceFilePosition>()
            .add(sourceFilePosition).add(*sourceFilePositions).build()
    )

    constructor(
        kind: Kind,
        text: String,
        rawMessage: String,
        toolName: String?,
        positions: ImmutableList<SourceFilePosition>
    ) : this(
        kind = kind,
        text = text,
        rawMessage = rawMessage,
        toolName = toolName,
        sourceFilePositions = if (positions.isEmpty()) {
            ImmutableList.of(SourceFilePosition.UNKNOWN)
        } else {
            positions
        }
    )

    @Deprecated("Used by kotlin plugin.")
    constructor(
        kind: Kind,
        text: String,
        rawMessage: String,
        toolName: com.google.common.base.Optional<String>,
        positions: ImmutableList<SourceFilePosition>
    ) : this(
        kind = kind,
        text = text,
        rawMessage = rawMessage,
        toolName = toolName.orNull(),
        sourceFilePositions = if (positions.isEmpty()) {
            ImmutableList.of(SourceFilePosition.UNKNOWN)
        } else {
            positions
        }
    )

    enum class Kind {
        ERROR, WARNING, INFO, STATISTICS, UNKNOWN, SIMPLE;

        companion object {
            @JvmStatic
            fun findIgnoringCase(s: String, defaultKind: Kind?): Kind? {
                for (kind in values()) {
                    if (kind.toString().equals(s, ignoreCase = true)) {
                        return kind
                    }
                }
                return defaultKind
            }
        }
    }
}