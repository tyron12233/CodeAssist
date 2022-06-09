package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;

import java.util.Collections;
import java.util.List;

public class DefaultWorkInProgressFormatter {
    private final static List<StyledTextOutputEvent.Span> IDLE_SPANS = Collections.singletonList(new StyledTextOutputEvent.Span("> IDLE"));
    private final ConsoleMetaData consoleMetaData;

    public DefaultWorkInProgressFormatter(ConsoleMetaData consoleMetaData) {
        this.consoleMetaData = consoleMetaData;
    }

    public List<StyledTextOutputEvent.Span> format(ProgressOperation op) {
        StringBuilder builder = new StringBuilder();
        ProgressOperation current = op;
        while (current != null && !"com.tyron.builder.internal.progress.BuildProgressLogger".equals(current.getCategory())) {
            String message = current.getMessage();
            current = current.getParent();

            if (message == null) {
                continue;
            }

            builder.insert(0, " > ").insert(3, message);
        }
        if (builder.length() > 0) {
            builder.delete(0, 1);
        } else {
            return IDLE_SPANS;
        }

        return Collections.singletonList(new StyledTextOutputEvent.Span(StyledTextOutput.Style.Header, trim(builder)));
    }

    public List<StyledTextOutputEvent.Span> format() {
        return IDLE_SPANS;
    }

    private String trim(StringBuilder formattedString) {
        // Don't write to the right-most column, as on some consoles the cursor will wrap to the next line and currently wrapping causes
        // layout weirdness
        int maxWidth;
        if (consoleMetaData.getCols() > 0) {
            maxWidth = consoleMetaData.getCols() - 1;
        } else {
            // Assume 80 wide. This is to minimize wrapping on console where we don't know the width (eg mintty)
            // It's not intended to be a correct solution, simply a work around
            maxWidth = 79;
        }
        if (maxWidth < formattedString.length()) {
            return formattedString.substring(0, maxWidth);
        }
        return formattedString.toString();
    }
}
