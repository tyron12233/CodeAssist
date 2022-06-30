package com.tyron.builder.internal.logging.format;

import static com.tyron.builder.internal.logging.events.StyledTextOutputEvent.EOL;

import com.google.common.collect.Lists;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.logging.text.StyledTextOutput;

import java.util.List;

public class PrettyPrefixedLogHeaderFormatter implements LogHeaderFormatter {
    @Override
    public List<StyledTextOutputEvent.Span> format(String description, String status, boolean failed) {
        if (status.isEmpty()) {
            return Lists.newArrayList(header(description, failed), EOL);
        } else {
            return Lists.newArrayList(header(description, failed), status(status, failed), EOL);
        }
    }

    private StyledTextOutputEvent.Span header(String message, boolean failed) {
        StyledTextOutput.Style messageStyle = failed ? StyledTextOutput.Style.FailureHeader : StyledTextOutput.Style.Header;
        return new StyledTextOutputEvent.Span(messageStyle, "> " + message);
    }

    private StyledTextOutputEvent.Span status(String status, boolean failed) {
        StyledTextOutput.Style statusStyle = failed ? StyledTextOutput.Style.Failure : StyledTextOutput.Style.Info;
        return new StyledTextOutputEvent.Span(statusStyle, " " + status);
    }
}
