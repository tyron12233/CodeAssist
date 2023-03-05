package org.gradle.internal.logging.console;

import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.PromptOutputEvent;

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
