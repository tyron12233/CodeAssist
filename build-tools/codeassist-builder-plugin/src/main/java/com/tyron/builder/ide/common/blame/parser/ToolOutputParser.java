package com.tyron.builder.ide.common.blame.parser;

import com.android.annotations.NonNull;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.SourceFilePosition;
import com.android.utils.ILogger;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.tyron.builder.ide.common.blame.parser.util.OutputLineReader;

import java.util.*;

public class ToolOutputParser {

    @NonNull
    private final List<PatternAwareOutputParser> mParsers;

    @NonNull
    private final ILogger mLogger;

    @NonNull
    private final Message.Kind mUnparsedMessageKind;

    public ToolOutputParser(@NonNull Iterable<PatternAwareOutputParser> parsers, @NonNull ILogger logger) {
        this(ImmutableList.copyOf(parsers), Message.Kind.SIMPLE, logger);
    }

    public ToolOutputParser(@NonNull PatternAwareOutputParser [] parsers, @NonNull ILogger logger) {
        this(ImmutableList.copyOf(parsers), Message.Kind.SIMPLE, logger);
    }

    public ToolOutputParser(@NonNull PatternAwareOutputParser parser, @NonNull ILogger logger) {
        this(ImmutableList.of(parser), Message.Kind.SIMPLE, logger);
    }

    public ToolOutputParser(@NonNull PatternAwareOutputParser parser,
            @NonNull Message.Kind unparsedMessageKind, @NonNull ILogger logger) {
        this(ImmutableList.of(parser), unparsedMessageKind, logger);
    }

    private ToolOutputParser(@NonNull ImmutableList<PatternAwareOutputParser> parsers,
            @NonNull Message.Kind unparsedMessageKind,
            @NonNull ILogger logger) {
        mParsers = parsers;
        mUnparsedMessageKind = unparsedMessageKind;
        mLogger = logger;
    }

    public List<Message> parseToolOutput(@NonNull String output) {
        return parseToolOutput(output, false);
    }

    public List<Message> parseToolOutput(@NonNull String output, boolean ignoreUnrecognizedText) {
        OutputLineReader outputReader = new OutputLineReader(output);

        if (outputReader.getLineCount() == 0) {
            return Collections.emptyList();
        }

        List<Message> messages = Lists.newArrayList();
        String line;
        while ((line = outputReader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            boolean handled = false;
            for (PatternAwareOutputParser parser : mParsers) {
                try {
                    if (parser.parse(line, outputReader, messages, mLogger)) {
                        handled = true;
                        break;
                    }
                }
                catch (ParsingFailedException e) {
                    return Collections.emptyList();
                }
            }
            if (handled) {
                int messageCount = messages.size();
                if (messageCount > 0) {
                    Message last = messages.get(messageCount - 1);
                    if (last.getText().contains("Build cancelled")) {
                        // Build was cancelled, just quit. Extra messages are just confusing noise.
                        break;
                    }
                }
            }
            else if (!ignoreUnrecognizedText) {
                // If none of the standard parsers recognize the input, include it as info such
                // that users don't miss potentially vital output such as gradle plugin exceptions.
                // If there is predictable useless input we don't want to appear here, add a custom
                // parser to digest it.
                messages.add(new Message(mUnparsedMessageKind, line, SourceFilePosition.UNKNOWN));
            }
        }
        // Remove duplicates b/67421644
        LinkedHashSet<Message> uniqueMessages = new LinkedHashSet<>(messages);
        return Lists.newArrayList(uniqueMessages);
    }
}