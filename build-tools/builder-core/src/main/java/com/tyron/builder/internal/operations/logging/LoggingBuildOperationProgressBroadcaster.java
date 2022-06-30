package com.tyron.builder.internal.operations.logging;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.logging.events.CategorisedOutputEvent;
import com.tyron.builder.internal.logging.events.LogEvent;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.logging.sink.OutputEventListenerManager;
import com.tyron.builder.internal.operations.BuildOperationProgressEventEmitter;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * Emits build operation progress for events that represent logging.
 *
 * Uses the existing {@link OutputEventListener} to observe events that <i>may</i> cause console output,
 * and emits build operation progress events for those that do cause console output.
 *
 * Currently, the only audience of these events is the build scan plugin.
 * It is concerned with recreating the <i>plain</i> console for an invocation,
 * and associating logging output with tasks, projects, and other logical entities.
 * It does not attempt to emulate the rich console.
 *
 * This solution has some quirks due to how the console output subsystem in Gradle has evolved.
 *
 * An “output event” effectively represents something of interest happening that
 * some observer may wish to know about in order to visualise what is happening, e.g. a console renderer.
 * We are integrating at this level, but imposing different semantics.
 * We only broadcast the subset of events that influence the “plain console”, because this is all we need right now.
 * The build scan infrastructure has some knowledge of how different versions of Gradle respond to these events
 * with regard to console rendering and effectively emulate.
 *
 * Ideally, we would emit a more concrete model.
 * This would be something like more clearly separating logging output from “user code” from Gradle's “UI” output,
 * and separately observing it from rendering instructions.
 * This may come later.
 *
 * @since 4.7
 */
@ServiceScope(Scopes.BuildSession.class)
public class LoggingBuildOperationProgressBroadcaster implements Stoppable, OutputEventListener {

    private final OutputEventListenerManager outputEventListenerManager;
    private final BuildOperationProgressEventEmitter progressEventEmitter;

    @VisibleForTesting
    OperationIdentifier rootBuildOperation;

    public LoggingBuildOperationProgressBroadcaster(OutputEventListenerManager outputEventListenerManager, BuildOperationProgressEventEmitter progressEventEmitter) {
        this.outputEventListenerManager = outputEventListenerManager;
        this.progressEventEmitter = progressEventEmitter;
        outputEventListenerManager.setListener(this);
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof RenderableOutputEvent) {
            RenderableOutputEvent renderableOutputEvent = (RenderableOutputEvent) event;
            OperationIdentifier operationIdentifier = renderableOutputEvent.getBuildOperationId();
            if (operationIdentifier == null) {
                if (rootBuildOperation == null) {
                    return;
                }
                operationIdentifier = rootBuildOperation;
            }
            if (renderableOutputEvent instanceof StyledTextOutputEvent || renderableOutputEvent instanceof LogEvent) {
                emit(renderableOutputEvent, operationIdentifier);
            }
        } else if (event instanceof ProgressStartEvent) {
            ProgressStartEvent progressStartEvent = (ProgressStartEvent) event;
            if (progressStartEvent.getLoggingHeader() == null) {
                return; // If the event has no logging header, it doesn't manifest as console output.
            }
            OperationIdentifier operationIdentifier = progressStartEvent.getBuildOperationId();
            if (operationIdentifier == null && rootBuildOperation != null) {
                operationIdentifier = rootBuildOperation;
            }
            emit(progressStartEvent, operationIdentifier);
        }
    }

    private void emit(CategorisedOutputEvent event, OperationIdentifier buildOperationId) {
        progressEventEmitter.emit(
            buildOperationId,
            event.getTimestamp(),
            event
        );
    }

    @Override
    public void stop() {
        outputEventListenerManager.removeListener(this);
    }

    public void rootBuildOperationStarted() {
        rootBuildOperation = CurrentBuildOperationRef.instance().getId();
    }
}
