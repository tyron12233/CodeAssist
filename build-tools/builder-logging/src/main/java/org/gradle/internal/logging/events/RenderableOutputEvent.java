package org.gradle.internal.logging.events;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.api.logging.LogLevel;

import org.jetbrains.annotations.Nullable;

public abstract class RenderableOutputEvent extends CategorisedOutputEvent {
    private OperationIdentifier buildOperationId;

    protected RenderableOutputEvent(long timestamp, String category, LogLevel logLevel, @Nullable OperationIdentifier buildOperationId) {
        super(timestamp, category, logLevel);
        this.buildOperationId = buildOperationId;
    }

    /**
     * Renders this event to the given output. The output's style will be set to {@link
     * StyledTextOutput.Style#Normal}. The style will be reset after the rendering is complete, so
     * there is no need for this method to clean up the style.
     *
     * @param output The output to render to.
     */
    public abstract void render(StyledTextOutput output);

    @Nullable
    public OperationIdentifier getBuildOperationId() {
        return buildOperationId;
    }

    /**
     * Creates a copy of this event with the given build operation id.
     */
    public abstract RenderableOutputEvent withBuildOperationId(OperationIdentifier buildOperationId);
}
