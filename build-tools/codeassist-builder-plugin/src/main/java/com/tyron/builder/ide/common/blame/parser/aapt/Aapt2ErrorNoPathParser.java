package com.tyron.builder.ide.common.blame.parser.aapt;

import com.android.annotations.NonNull;
import com.android.ide.common.blame.Message;
import com.android.utils.ILogger;
import com.tyron.builder.ide.common.blame.parser.ParsingFailedException;
import com.tyron.builder.ide.common.blame.parser.util.OutputLineReader;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catch all errors that do not have a file path in them. */
public class Aapt2ErrorNoPathParser extends AbstractAaptOutputParser {
    /**
     * Single-line aapt error containing a path without a file path.
     *
     * <pre>
     * ERROR: <error>
     * </pre>
     */
    private static final Pattern MSG_PATTERN = Pattern.compile("^ERROR: (.+)$");

    @Override
    public boolean parse(
            @NonNull String line,
            @NonNull OutputLineReader reader,
            @NonNull List<Message> messages,
            @NonNull ILogger logger)
            throws ParsingFailedException {
        Matcher m = MSG_PATTERN.matcher(line);
        if (!m.matches()) {
            return false;
        }
        String msgText = m.group(1);

        Message msg = createMessage(Message.Kind.ERROR, msgText, null, null, "", logger);
        messages.add(msg);
        return true;
    }
}