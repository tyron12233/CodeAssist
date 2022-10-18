package org.gradle.internal.logging.format;


import org.gradle.internal.logging.events.StyledTextOutputEvent;

import java.util.List;

public interface LogHeaderFormatter {
    /**
     * Given a message, return possibly-styled output for displaying message meant to categorize
     * other messages "below" it, if any.
     */
    List<StyledTextOutputEvent.Span> format(String description, String status, boolean failed);
}
