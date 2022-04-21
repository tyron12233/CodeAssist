package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.PromptOutputEvent;
import com.tyron.builder.internal.logging.events.UserInputRequestEvent;
import com.tyron.builder.internal.logging.events.UserInputResumeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

public abstract class AbstractUserInputRenderer implements OutputEventListener {
    final OutputEventListener delegate;
    private final List<OutputEvent> eventQueue = new ArrayList<OutputEvent>();
    private boolean paused;

    public AbstractUserInputRenderer(OutputEventListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof UserInputRequestEvent) {
            handleUserInputRequestEvent();
            return;
        } else if (event instanceof UserInputResumeEvent) {
            handleUserInputResumeEvent();
            return;
        } else if (event instanceof PromptOutputEvent) {
            handlePrompt((PromptOutputEvent) event);
            return;
        }

        if (paused) {
            eventQueue.add(event);
            return;
        }

        delegate.onOutput(event);
    }

    private void handleUserInputRequestEvent() {
        startInput();
        paused = true;
    }

    private void handleUserInputResumeEvent() {
        if (!paused) {
            throw new IllegalStateException("Cannot resume user input if not paused yet");
        }

        paused = false;
        finishInput();
        replayEvents();
    }

    private void replayEvents() {
        ListIterator<OutputEvent> iterator = eventQueue.listIterator();

        while (iterator.hasNext()) {
            delegate.onOutput(iterator.next());
            iterator.remove();
        }
    }

    List<OutputEvent> getEventQueue() {
        return eventQueue;
    }

    abstract void startInput();

    abstract void handlePrompt(PromptOutputEvent event);

    abstract void finishInput();
}

