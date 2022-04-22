package com.tyron.builder.internal.logging.format;


import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;

import java.util.List;

public interface LogHeaderFormatter {
    /**
     * Given a message, return possibly-styled output for displaying message meant to categorize
     * other messages "below" it, if any.
     */
    List<StyledTextOutputEvent.Span> format(String description, String status, boolean failed);
}
