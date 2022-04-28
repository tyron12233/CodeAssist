package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.api.logging.LogLevel;

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
