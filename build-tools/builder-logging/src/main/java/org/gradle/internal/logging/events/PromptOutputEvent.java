package org.gradle.internal.logging.events;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.events.RenderableOutputEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.api.logging.LogLevel;

public class PromptOutputEvent extends RenderableOutputEvent {

    private final String prompt;

    public PromptOutputEvent(long timestamp, String prompt) {
        super(timestamp, "prompt", LogLevel.QUIET, null);
        this.prompt = prompt;
    }

    @Override
    public void render(StyledTextOutput output) {
        output.text(prompt);
    }

    public String getPrompt() {
        return prompt;
    }

    @Override
    public String toString() {
        return "[" + getLogLevel() + "] [" + getCategory() + "] " + prompt;
    }

    @Override
    public RenderableOutputEvent withBuildOperationId(OperationIdentifier buildOperationId) {
        throw new UnsupportedOperationException();
    }
}
