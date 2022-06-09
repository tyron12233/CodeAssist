package com.tyron.builder.internal.logging.sink;

import static com.tyron.builder.internal.logging.text.StyledTextOutput.*;

import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.util.GUtil;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.SystemProperties;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@code com.tyron.builder.logging.internal.OutputEventListener} implementation which generates output events to log the
 * progress of operations.
 */
public class ProgressLogEventGenerator implements OutputEventListener {
    private static final String EOL = SystemProperties.getInstance().getLineSeparator();

    private final OutputEventListener listener;
    private final Map<OperationIdentifier, Operation> operations = new LinkedHashMap<OperationIdentifier, Operation>();

    public ProgressLogEventGenerator(OutputEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof ProgressStartEvent) {
            onStart((ProgressStartEvent) event);
        } else if (event instanceof ProgressCompleteEvent) {
            onComplete((ProgressCompleteEvent) event);
        } else if (event instanceof RenderableOutputEvent) {
            doOutput((RenderableOutputEvent) event);
        } else if (!(event instanceof ProgressEvent)) {
            listener.onOutput(event);
        }
    }

    private void doOutput(RenderableOutputEvent event) {
        for (Operation operation : operations.values()) {
            operation.completeHeader();
        }
        listener.onOutput(event);
    }

    private void onComplete(ProgressCompleteEvent progressCompleteEvent) {
        if (operations.isEmpty()) {
            return;
        }
        Operation operation = operations.remove(progressCompleteEvent.getProgressOperationId());
        if (operation == null) {
            return;
        }
        completeOperation(progressCompleteEvent, operation);
    }

    private void completeOperation(ProgressCompleteEvent progressCompleteEvent, Operation operation) {
        operation.status = progressCompleteEvent.getStatus();
        operation.completeTime = progressCompleteEvent.getTimestamp();
        operation.complete();
    }

    private void onStart(ProgressStartEvent progressStartEvent) {
        Operation operation = new Operation(progressStartEvent.getCategory(), progressStartEvent.getLoggingHeader(), progressStartEvent.getTimestamp(), progressStartEvent.getBuildOperationId());
        operations.put(progressStartEvent.getProgressOperationId(), operation);
    }

    enum State {None, HeaderStarted, HeaderCompleted, Completed}

    private class Operation {
        private final OperationIdentifier buildOperationIdentifier;
        private final String category;
        private final String loggingHeader;
        private final long startTime;
        private final boolean hasLoggingHeader;
        private String status = "";
        private State state = State.None;
        private long completeTime;

        private Operation(String category, String loggingHeader, long startTime, OperationIdentifier buildOperationIdentifier) {
            this.category = category;
            this.loggingHeader = loggingHeader;
            this.startTime = startTime;
            this.hasLoggingHeader = GUtil.isTrue(loggingHeader);
            this.buildOperationIdentifier = buildOperationIdentifier;
        }

        private StyledTextOutputEvent plainTextEvent(long timestamp, String text) {
            return new StyledTextOutputEvent(timestamp, category, LogLevel.LIFECYCLE, buildOperationIdentifier, Collections.singletonList(new StyledTextOutputEvent.Span(text)));
        }

        private StyledTextOutputEvent styledTextEvent(long timestamp, StyledTextOutputEvent.Span... spans) {
            return new StyledTextOutputEvent(timestamp, category, LogLevel.LIFECYCLE, buildOperationIdentifier, Arrays.asList(spans));
        }

        private void doOutput(RenderableOutputEvent event) {
            for (Operation pending : operations.values()) {
                if (pending == this) {
                    break;
                }
                pending.completeHeader();
            }
            listener.onOutput(event);
        }

        public void startHeader() {
            assert state == State.None;
            if (hasLoggingHeader) {
                state = State.HeaderStarted;
                doOutput(plainTextEvent(startTime, loggingHeader));
            } else {
                state = State.HeaderCompleted;
            }
        }

        public void completeHeader() {
            switch (state) {
                case None:
                    if (hasLoggingHeader) {
                        listener.onOutput(plainTextEvent(startTime, loggingHeader + EOL));
                    }
                    break;
                case HeaderStarted:
                    listener.onOutput(plainTextEvent(startTime, EOL));
                    break;
                case HeaderCompleted:
                    return;
                default:
                    throw new IllegalStateException("state is " + state);
            }
            state = State.HeaderCompleted;
        }

        public void complete() {
            boolean hasStatus = GUtil.isTrue(status);
            switch (state) {
                case None:
                    if (hasLoggingHeader && hasStatus) {
                        doOutput(styledTextEvent(completeTime,
                                new StyledTextOutputEvent.Span(loggingHeader + ' '),
                                new StyledTextOutputEvent.Span(Style.ProgressStatus, status),
                                new StyledTextOutputEvent.Span(EOL)));
                    } else if (hasLoggingHeader) {
                        doOutput(plainTextEvent(completeTime, loggingHeader + EOL));
                    }
                    break;
                case HeaderStarted:
                    assert hasLoggingHeader;
                    if (hasStatus) {
                        doOutput(styledTextEvent(completeTime,
                                new StyledTextOutputEvent.Span(" "),
                                new StyledTextOutputEvent.Span(Style.ProgressStatus, status),
                                new StyledTextOutputEvent.Span(EOL)));
                    } else {
                        doOutput(plainTextEvent(completeTime, EOL));
                    }
                    break;
                case HeaderCompleted:
                    if (hasLoggingHeader && hasStatus) {
                        doOutput(styledTextEvent(completeTime,
                                new StyledTextOutputEvent.Span(loggingHeader + ' '),
                                new StyledTextOutputEvent.Span(Style.ProgressStatus, status),
                                new StyledTextOutputEvent.Span(EOL)));
                    }
                    break;
                default:
                    throw new IllegalStateException("state is " + state);
            }
            state = State.Completed;
        }
    }
}
