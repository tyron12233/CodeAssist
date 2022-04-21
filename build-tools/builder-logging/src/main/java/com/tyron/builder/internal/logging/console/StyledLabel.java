package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;

import java.util.List;

/**
 * A label where its text can be styled.
 */
public interface StyledLabel extends Label {
    void setText(List<StyledTextOutputEvent.Span> spans);
    void setText(StyledTextOutputEvent.Span span);
}
