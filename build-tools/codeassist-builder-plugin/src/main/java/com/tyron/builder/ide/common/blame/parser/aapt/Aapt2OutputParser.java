package com.tyron.builder.ide.common.blame.parser.aapt;

import com.android.annotations.NonNull;
import com.android.ide.common.blame.Message;
import com.android.utils.ILogger;
import com.tyron.builder.ide.common.blame.parser.ParsingFailedException;
import com.tyron.builder.ide.common.blame.parser.PatternAwareOutputParser;
import com.tyron.builder.ide.common.blame.parser.util.OutputLineReader;

import java.util.List;
import java.util.Map;

/** Parses AAPT2 output. */
public class Aapt2OutputParser implements PatternAwareOutputParser {

    private final AbstractAaptOutputParser[] parsers;

    public Aapt2OutputParser() {
        parsers =
                new AbstractAaptOutputParser[] {
                    new Aapt2ErrorParser(), new Aapt2ErrorNoPathParser()
                };
    }

    public Aapt2OutputParser(Map<String, String> identifiedSourceSets) {
        parsers =
                new AbstractAaptOutputParser[] {
                    new Aapt2ErrorParser(identifiedSourceSets), new Aapt2ErrorNoPathParser()
                };
    }

    @Override
    public boolean parse(
            @NonNull String line,
            @NonNull OutputLineReader reader,
            @NonNull List<Message> messages,
            @NonNull ILogger logger) {
        String trimmedLine = line.trim();
        for (AbstractAaptOutputParser parser : parsers) {
            try {
                if (parser.parse(trimmedLine, reader, messages, logger)) {
                    return true;
                }
            } catch (ParsingFailedException e) {
                // If there's an exception, it means a parser didn't like the input, so just ignore
                // and let other parsers have a crack at it.
            }
        }
        return false;
    }
}