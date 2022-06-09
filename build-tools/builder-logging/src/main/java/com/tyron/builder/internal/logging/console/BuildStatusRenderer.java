package com.tyron.builder.internal.logging.console;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.internal.logging.events.EndOutputEvent;
import com.tyron.builder.internal.logging.events.FlushOutputEvent;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.logging.events.UpdateNowEvent;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.OperationIdentifier;

import java.util.HashSet;
import java.util.Set;

/**
 * <p>This listener displays nothing unless it receives periodic {@link UpdateNowEvent} clock events.</p>
 */
public class BuildStatusRenderer implements OutputEventListener {
    public static final int PROGRESS_BAR_WIDTH = 13;
    public static final String PROGRESS_BAR_PREFIX = "<";
    public static final char PROGRESS_BAR_COMPLETE_CHAR = '=';
    public static final char PROGRESS_BAR_INCOMPLETE_CHAR = '-';
    public static final String PROGRESS_BAR_SUFFIX = ">";

    private enum Phase {
        Initializing, Configuring, Executing
    }

    private final OutputEventListener listener;
    private final StyledLabel buildStatusLabel;
    private final Console console;
    private final ConsoleMetaData consoleMetaData;
    private OperationIdentifier buildProgressOperationId;
    private Phase currentPhase;
    private final Set<OperationIdentifier> currentPhaseChildren = new HashSet<OperationIdentifier>();
    private long currentTimePeriod;

    // What actually shows up on the console
    private ProgressBar progressBar;

    // Used to maintain timer
    private long buildStartTimestamp;
    private boolean timerEnabled;

    public BuildStatusRenderer(OutputEventListener listener, StyledLabel buildStatusLabel, Console console, ConsoleMetaData consoleMetaData) {
        this.listener = listener;
        this.buildStatusLabel = buildStatusLabel;
        this.console = console;
        this.consoleMetaData = consoleMetaData;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof ProgressStartEvent) {
            ProgressStartEvent startEvent = (ProgressStartEvent) event;
            if (startEvent.isBuildOperationStart()) {
                if (buildStartTimestamp == 0 && startEvent.getParentProgressOperationId() == null) {
                    // The very first event starts the Initializing phase
                    // TODO - should use BuildRequestMetaData to determine the build start time
                    buildStartTimestamp = startEvent.getTimestamp();
                    buildProgressOperationId = startEvent.getProgressOperationId();
                    phaseStarted(startEvent, Phase.Initializing);
                } else if (startEvent.getBuildOperationCategory() == BuildOperationCategory.CONFIGURE_ROOT_BUILD) {
                    // Once the root build starts configuring, we are in Configuring phase
                    phaseStarted(startEvent, Phase.Configuring);
                } else if (startEvent.getBuildOperationCategory() == BuildOperationCategory.CONFIGURE_BUILD && currentPhase == Phase.Configuring) {
                    // Any configuring event received from nested or buildSrc builds before the root build starts configuring is ignored
                    phaseHasMoreProgress(startEvent);
                } else if (startEvent.getBuildOperationCategory() == BuildOperationCategory.CONFIGURE_PROJECT && currentPhase == Phase.Configuring) {
                    // Any configuring event received from nested or buildSrc builds before the root build starts configuring is ignored
                    currentPhaseChildren.add(startEvent.getProgressOperationId());
                } else if (startEvent.getBuildOperationCategory() == BuildOperationCategory.RUN_MAIN_TASKS) {
                    phaseStarted(startEvent, Phase.Executing);
                } else if (startEvent.getBuildOperationCategory() == BuildOperationCategory.RUN_WORK && currentPhase == Phase.Executing) {
                    // Any work execution happening in nested or buildSrc builds before the root build has started executing work is ignored
                    phaseHasMoreProgress(startEvent);
                } else if (startEvent.getBuildOperationCategory().isTopLevelWorkItem() && currentPhase == Phase.Executing) {
                    // Any work execution happening in nested or buildSrc builds before the root build has started executing work is ignored
                    currentPhaseChildren.add(startEvent.getProgressOperationId());
                }
            }
        } else if (event instanceof ProgressCompleteEvent) {
            ProgressCompleteEvent completeEvent = (ProgressCompleteEvent) event;
            if (completeEvent.getProgressOperationId().equals(buildProgressOperationId)) {
                buildEnded();
            } else if (currentPhaseChildren.remove(completeEvent.getProgressOperationId())) {
                phaseProgressed(completeEvent);
            }
        }

        listener.onOutput(event);

        if (event instanceof UpdateNowEvent) {
            currentTimePeriod = ((UpdateNowEvent) event).getTimestamp();
            renderNow(currentTimePeriod);
        } else if (event instanceof EndOutputEvent || event instanceof FlushOutputEvent) {
            renderNow(currentTimePeriod);
        }
    }

    private void renderNow(long now) {
        if (progressBar != null) {
            buildStatusLabel.setText(progressBar.formatProgress(timerEnabled, now - buildStartTimestamp));
        }
        console.flush();
    }

    private void phaseStarted(ProgressStartEvent progressStartEvent, Phase phase) {
        timerEnabled = true;
        currentPhase = phase;
        currentPhaseChildren.clear();
        progressBar = newProgressBar(phase.name().toUpperCase(), 0, progressStartEvent.getTotalProgress());
    }

    private void phaseHasMoreProgress(ProgressStartEvent progressStartEvent) {
        progressBar.moreProgress(progressStartEvent.getTotalProgress());
    }

    private void phaseProgressed(ProgressCompleteEvent progressEvent) {
        if (progressBar != null) {
            progressBar.update(progressEvent.isFailed());
        }
    }

    private void buildEnded() {
        progressBar = newProgressBar("WAITING", 0, 1);
        currentPhase = null;
        buildProgressOperationId = null;
        currentPhaseChildren.clear();
        timerEnabled = false;
    }

    @VisibleForTesting
    public ProgressBar newProgressBar(String initialSuffix, int initialProgress, int totalProgress) {
        return new ProgressBar(consoleMetaData,
                PROGRESS_BAR_PREFIX,
                PROGRESS_BAR_WIDTH,
                PROGRESS_BAR_SUFFIX,
                PROGRESS_BAR_COMPLETE_CHAR,
                PROGRESS_BAR_INCOMPLETE_CHAR,
                initialSuffix, initialProgress, totalProgress);
    }
}
