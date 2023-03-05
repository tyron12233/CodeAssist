package com.tyron.builder.ide.common.blame.parser;

import com.android.annotations.NonNull;
import com.android.ide.common.blame.Message;
import com.android.utils.ILogger;
import com.tyron.builder.ide.common.blame.parser.util.OutputLineReader;

import java.util.List;

/**
 * Parses the build output. Implementations are specialized in particular output patterns.
 */
public interface PatternAwareOutputParser {

    /**
     * Parses the given output line.
     *
     * @param line     the line to parse.
     * @param reader   passed in case this parser needs to parse more lines in order to create a
     *                 {@code Message}.
     * @param messages stores the messages created during parsing, if any.
     * @return {@code true} if this parser was able to parser the given line, {@code false}
     * otherwise.
     * @throws ParsingFailedException if something goes wrong (e.g. malformed output.)
     */
    boolean parse(@NonNull String line, @NonNull OutputLineReader reader,
            @NonNull List<Message> messages, @NonNull ILogger logger)
            throws ParsingFailedException;
}