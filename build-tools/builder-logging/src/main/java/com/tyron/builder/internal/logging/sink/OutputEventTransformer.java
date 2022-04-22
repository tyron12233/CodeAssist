package com.tyron.builder.internal.logging.sink;


import com.tyron.builder.util.GUtil;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Transforms the stream of output events to discard progress operations that are not interesting to the logging subsystem. This reduces the amount of work that downstream consumers have to do to process the stream. For example, these discarded events don't need to be written to the daemon client.
 */
public class OutputEventTransformer implements OutputEventListener {
    // A map from progress operation id seen in event -> progress operation id that should be forwarded
    private final Map<OperationIdentifier, OperationIdentifier> effectiveProgressOperation = new HashMap<OperationIdentifier, OperationIdentifier>();
    // A set of progress operations that have been forwarded
    private final Set<OperationIdentifier> forwarded = new HashSet<OperationIdentifier>();

    private final OutputEventListener listener;

    public OutputEventTransformer(OutputEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof ProgressStartEvent) {
            ProgressStartEvent startEvent = (ProgressStartEvent) event;
            if (!startEvent.isBuildOperationStart()) {
                forwarded.add(startEvent.getProgressOperationId());
                OperationIdentifier parentProgressOperationId = startEvent.getParentProgressOperationId();
                if (parentProgressOperationId != null) {
                    OperationIdentifier mappedId = effectiveProgressOperation.get(parentProgressOperationId);
                    if (mappedId != null) {
                        startEvent = startEvent.withParentProgressOperation(mappedId);
                    }
                }
                listener.onOutput(startEvent);
                return;
            }

            if (startEvent.getParentProgressOperationId() == null || GUtil
                    .isTrue(startEvent.getLoggingHeader()) || GUtil.isTrue(startEvent.getStatus()) || startEvent.getBuildOperationCategory() != BuildOperationCategory.UNCATEGORIZED) {
                forwarded.add(startEvent.getProgressOperationId());
                OperationIdentifier parentProgressOperationId = startEvent.getParentProgressOperationId();
                if (parentProgressOperationId != null) {
                    OperationIdentifier mappedId = effectiveProgressOperation.get(parentProgressOperationId);
                    if (mappedId != null) {
                        startEvent = startEvent.withParentProgressOperation(mappedId);
                    }
                }
                listener.onOutput(startEvent);
            } else {
                // Ignore this progress operation, and map any reference to it to its parent (or whatever its parent is mapped to)
                OperationIdentifier mappedParent = effectiveProgressOperation.get(startEvent.getParentProgressOperationId());
                if (mappedParent == null) {
                    mappedParent = startEvent.getParentProgressOperationId();
                }
                effectiveProgressOperation.put(startEvent.getProgressOperationId(), mappedParent);
            }
        } else if (event instanceof ProgressCompleteEvent) {
            ProgressCompleteEvent completeEvent = (ProgressCompleteEvent) event;
            OperationIdentifier mappedEvent = effectiveProgressOperation.remove(completeEvent.getProgressOperationId());
            if (mappedEvent == null && forwarded.remove(completeEvent.getProgressOperationId())) {
                listener.onOutput(event);
            }
        } else if (event instanceof ProgressEvent) {
            ProgressEvent progressEvent = (ProgressEvent) event;
            if (forwarded.contains(progressEvent.getProgressOperationId())) {
                listener.onOutput(event);
            }
        } else if (event instanceof RenderableOutputEvent) {
            RenderableOutputEvent outputEvent = (RenderableOutputEvent) event;
            OperationIdentifier operationId = outputEvent.getBuildOperationId();
            if (operationId != null) {
                OperationIdentifier mappedId = effectiveProgressOperation.get(operationId);
                if (mappedId != null) {
                    outputEvent = outputEvent.withBuildOperationId(mappedId);
                }
            }
            listener.onOutput(outputEvent);
        } else {
            listener.onOutput(event);
        }
    }
}
