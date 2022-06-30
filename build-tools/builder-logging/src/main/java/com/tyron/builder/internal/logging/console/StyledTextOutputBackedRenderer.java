package com.tyron.builder.internal.logging.console;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.logging.events.LogLevelChangeEvent;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.internal.logging.text.AbstractLineChoppingStyledTextOutput;
import com.tyron.builder.internal.logging.text.StyledTextOutput;

import java.text.SimpleDateFormat;
import java.util.Date;

import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.Error;
import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.Normal;

public class StyledTextOutputBackedRenderer implements OutputEventListener {
    public static final String ISO_8601_DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    private final OutputEventTextOutputImpl textOutput;
    private boolean debugOutput;
    private SimpleDateFormat dateFormat;
    private RenderableOutputEvent lastEvent;

    public StyledTextOutputBackedRenderer(StyledTextOutput textOutput) {
        this.textOutput = new OutputEventTextOutputImpl(textOutput);
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof LogLevelChangeEvent) {
            LogLevelChangeEvent changeEvent = (LogLevelChangeEvent) event;
            boolean newLogLevelIsDebug = changeEvent.getNewLogLevel() == LogLevel.DEBUG;
            if (newLogLevelIsDebug && dateFormat == null) {
                dateFormat = new SimpleDateFormat(ISO_8601_DATE_TIME_FORMAT);
            }
            debugOutput = newLogLevelIsDebug;
        }
        if (event instanceof RenderableOutputEvent) {
            RenderableOutputEvent outputEvent = (RenderableOutputEvent) event;
            textOutput.style(outputEvent.getLogLevel() == LogLevel.ERROR ? Error : Normal);
            if (debugOutput && (textOutput.atEndOfLine || lastEvent == null || !lastEvent.getCategory().equals(outputEvent.getCategory()))) {
                if (!textOutput.atEndOfLine) {
                    textOutput.println();
                }
                textOutput.text(dateFormat.format(new Date(outputEvent.getTimestamp())));
                textOutput.text(" [");
                textOutput.text(outputEvent.getLogLevel());
                textOutput.text("] [");
                textOutput.text(outputEvent.getCategory());
                textOutput.text("] ");
            }
            outputEvent.render(textOutput);
            lastEvent = outputEvent;
            textOutput.style(Normal);
        }
    }

    private static class OutputEventTextOutputImpl extends AbstractLineChoppingStyledTextOutput {
        private final StyledTextOutput textOutput;
        private boolean atEndOfLine = true;

        public OutputEventTextOutputImpl(StyledTextOutput textOutput) {
            this.textOutput = textOutput;
        }

        @Override
        protected void doStyleChange(Style style) {
            textOutput.style(style);
        }

        @Override
        protected void doLineText(CharSequence text) {
            textOutput.text(text);
            atEndOfLine = false;
        }

        @Override
        protected void doEndLine(CharSequence endOfLine) {
            textOutput.text(endOfLine);
            atEndOfLine = true;
        }
    }
}

