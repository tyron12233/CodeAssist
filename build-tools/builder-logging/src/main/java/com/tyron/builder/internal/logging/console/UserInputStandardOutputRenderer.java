package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.PromptOutputEvent;

public class UserInputStandardOutputRenderer extends AbstractUserInputRenderer {
    public UserInputStandardOutputRenderer(OutputEventListener delegate) {
        super(delegate);
    }

    @Override
    void startInput() {
    }

    @Override
    void handlePrompt(PromptOutputEvent event) {
        delegate.onOutput(event);
    }

    @Override
    void finishInput() {
    }
}
