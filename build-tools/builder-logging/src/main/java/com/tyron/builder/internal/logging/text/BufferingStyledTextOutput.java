package com.tyron.builder.internal.logging.text;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.text.AbstractStyledTextOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link StyledTextOutput} which buffers the content written to it, for later forwarding to another {@link StyledTextOutput} instance.
 */
public class BufferingStyledTextOutput extends AbstractStyledTextOutput {
    private final List<Action<StyledTextOutput>> events = new ArrayList<Action<StyledTextOutput>>();
    private boolean hasContent;

    /**
     * Writes the buffered contents of this output to the given target, and clears the buffer.
     */
    public void writeTo(StyledTextOutput output) {
        for (Action<StyledTextOutput> event : events) {
            event.execute(output);
        }
        events.clear();
    }

    @Override
    protected void doStyleChange(final StyledTextOutput.Style style) {
        if (!events.isEmpty() && (events.get(events.size() - 1) instanceof ChangeStyleAction)) {
            events.remove(events.size() - 1);
        }
        events.add(new ChangeStyleAction(style));
    }

    @Override
    protected void doAppend(final String text) {
        if (text.length() == 0) {
            return;
        }
        hasContent = true;
        events.add(new Action<StyledTextOutput>() {
            @Override
            public void execute(StyledTextOutput styledTextOutput) {
                styledTextOutput.text(text);
            }
        });
    }

    public boolean getHasContent() {
        return hasContent;
    }

    private static class ChangeStyleAction implements Action<StyledTextOutput> {
        private final StyledTextOutput.Style style;

        public ChangeStyleAction(StyledTextOutput.Style style) {
            this.style = style;
        }

        @Override
        public void execute(StyledTextOutput styledTextOutput) {
            styledTextOutput.style(style);
        }
    }
}