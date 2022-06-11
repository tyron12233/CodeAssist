package com.tyron.builder.internal.logging.console;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.logging.events.EndOutputEvent;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.logging.events.UpdateNowEvent;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class WorkInProgressRenderer implements OutputEventListener {
    private final OutputEventListener listener;
    private final ProgressOperations operations = new ProgressOperations();
    private final BuildProgressArea progressArea;
    private final DefaultWorkInProgressFormatter labelFormatter;
    private final ConsoleLayoutCalculator consoleLayoutCalculator;

    private final List<OutputEvent> queue = new ArrayList<OutputEvent>();

    // Track all unused labels to display future progress operation
    private final Deque<StyledLabel> unusedProgressLabels;

    // Track currently associated label with its progress operation
    private final Map<OperationIdentifier, AssociationLabel> operationIdToAssignedLabels = new HashMap<OperationIdentifier, AssociationLabel>();

    // Track any progress operation that either can't be display due to label shortage or child progress operation is already been displayed
    private final Deque<ProgressOperation> unassignedProgressOperations = new ArrayDeque<ProgressOperation>();

    public WorkInProgressRenderer(OutputEventListener listener, BuildProgressArea progressArea, DefaultWorkInProgressFormatter labelFormatter, ConsoleLayoutCalculator consoleLayoutCalculator) {
        this.listener = listener;
        this.progressArea = progressArea;
        this.labelFormatter = labelFormatter;
        this.consoleLayoutCalculator = consoleLayoutCalculator;
        this.unusedProgressLabels = new ArrayDeque<StyledLabel>(progressArea.getBuildProgressLabels());
    }

    @Override
    public void onOutput(OutputEvent event) {
        queue.add(event);

        if (event instanceof UpdateNowEvent) {
            renderNow();
        } else if (event instanceof EndOutputEvent) {
            progressArea.setVisible(false);
        }

        listener.onOutput(event);
    }

    // Transform ProgressCompleteEvent into their corresponding progress OperationIdentifier.
    private Set<OperationIdentifier> toOperationIdSet(Iterable<ProgressCompleteEvent> events) {
        return StreamSupport.stream(events.spliterator(), false)
                .map(ProgressCompleteEvent::getProgressOperationId).collect(Collectors.toSet());
    }

    private void resizeTo(int newBuildProgressLabelCount) {
        int previousBuildProgressLabelCount = progressArea.getBuildProgressLabels().size();
        newBuildProgressLabelCount = consoleLayoutCalculator.calculateNumWorkersForConsoleDisplay(newBuildProgressLabelCount);
        if (previousBuildProgressLabelCount >= newBuildProgressLabelCount) {
            // We don't support shrinking at the moment
            return;
        }

        progressArea.resizeBuildProgressTo(newBuildProgressLabelCount);

        // Add new labels to the unused queue
        for (int i = newBuildProgressLabelCount - 1; i >= previousBuildProgressLabelCount; --i) {
            unusedProgressLabels.push(progressArea.getBuildProgressLabels().get(i));
        }
    }

    private void attach(ProgressOperation operation) {
        if (operation.hasChildren() || !isRenderable(operation)) {
            return;
        }

        // Don't show the parent operation while a child is visible
        // Instead, reuse the parent label, if any, for the child
        if (operation.getParent() != null) {
            unshow(operation.getParent());
        }

        // No more unused label? Try to resize.
        if (unusedProgressLabels.isEmpty()) {
            int newValue = operationIdToAssignedLabels.size() + 1;
            resizeTo(newValue);
            // At this point, the work-in-progress area may or may not have been resized due to maximum size constraint.
        }

        // Try to use a new label
        if (unusedProgressLabels.isEmpty()) {
            unassignedProgressOperations.add(operation);
        } else {
            attach(operation, unusedProgressLabels.pop());
        }
    }

    private void attach(ProgressOperation operation, StyledLabel label) {
        AssociationLabel association = new AssociationLabel(operation, label);
        operationIdToAssignedLabels.put(operation.getOperationId(), association);
    }

    // Declares that we're not following updates from this ProgressOperation anymore
    private void detach(ProgressOperation operation) {
        if (!isRenderable(operation)) {
            return;
        }

        unshow(operation);

        if (operation.getParent() != null && isRenderable(operation.getParent())) {
            attach(operation.getParent());
        } else if (!unassignedProgressOperations.isEmpty()) {
            attach(unassignedProgressOperations.pop());
        }
    }

    // Declares that we are stopping showing updates from this ProgressOperation.
    // We might be completely done following this ProgressOperation, or
    // we might simply be waiting for its children to complete.
    private void unshow(ProgressOperation operation) {
        OperationIdentifier operationId = operation.getOperationId();
        AssociationLabel association = operationIdToAssignedLabels.remove(operationId);
        if (association != null) {
            unusedProgressLabels.push(association.label);
        }
        unassignedProgressOperations.remove(operation);
    }

    // Any ProgressOperation in the parent chain has a message, the operation is considered renderable.
    private boolean isRenderable(ProgressOperation operation) {
        for (ProgressOperation current = operation; current != null; current = current.getParent()) {
            if (current.getMessage() != null) {
                return true;
            }
        }

        return false;
    }

    private void renderNow() {
        if (queue.isEmpty()) {
            return;
        }

        // Skip processing of any operations that both start and complete in the queue
        Set<OperationIdentifier> completeEventOperationIds = toOperationIdSet(Iterables.filter(queue, ProgressCompleteEvent.class));
        Set<OperationIdentifier> operationIdsToSkip = new HashSet<OperationIdentifier>();

        for (OutputEvent event : queue) {
            if (event instanceof ProgressStartEvent) {
                progressArea.setVisible(true);
                ProgressStartEvent startEvent = (ProgressStartEvent) event;
                if (completeEventOperationIds.contains(startEvent.getProgressOperationId())) {
                    operationIdsToSkip.add(startEvent.getProgressOperationId());
                    // Don't attach to any labels
                } else {
                    attach(operations.start(startEvent.getStatus(), startEvent.getCategory(), startEvent.getProgressOperationId(), startEvent.getParentProgressOperationId()));
                }
            } else if (event instanceof ProgressCompleteEvent) {
                ProgressCompleteEvent completeEvent = (ProgressCompleteEvent) event;
                if (!operationIdsToSkip.contains(completeEvent.getProgressOperationId())) {
                    detach(operations.complete(completeEvent.getProgressOperationId()));
                }
            } else if (event instanceof ProgressEvent) {
                ProgressEvent progressEvent = (ProgressEvent) event;
                if (!operationIdsToSkip.contains(progressEvent.getProgressOperationId())) {
                    operations.progress(progressEvent.getStatus(), progressEvent.getProgressOperationId());
                }
            }
        }
        queue.clear();

        for (AssociationLabel associatedLabel : operationIdToAssignedLabels.values()) {
            associatedLabel.renderNow();
        }
        for (StyledLabel emptyLabel : unusedProgressLabels) {
            emptyLabel.setText(labelFormatter.format());
        }
    }

    private class AssociationLabel {
        final ProgressOperation operation;
        final StyledLabel label;

        AssociationLabel(ProgressOperation operation, StyledLabel label) {
            this.operation = operation;
            this.label = label;
        }

        void renderNow() {
            label.setText(labelFormatter.format(operation));
        }
    }
}
