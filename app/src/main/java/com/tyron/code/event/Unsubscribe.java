package com.tyron.code.event;

import io.github.rosemoe.sora.event.Event;
import io.github.rosemoe.sora.event.EventReceiver;

/**
 * Instance for unsubscribing for a receiver.
 *
 * Note that this instance can be reused during an event dispatch, so
 * it is not a valid behavior to save the instance in event receivers.
 * Always use the one given by {@link EventReceiver#onReceive(Event, Unsubscribe)}.
 */
public class Unsubscribe {

    private boolean unsubscribeFlag = false;

    /**
     * Unsubscribe the event. And current receiver will not get event again.
     * References to the receiver are also removed.
     */
    public void unsubscribe() {
        unsubscribeFlag = true;
    }

    /**
     * Checks whether unsubscribe flag is set
     */
    public boolean isUnsubscribed() {
        return unsubscribeFlag;
    }

    /**
     * Reset the flag
     */
    public void reset() {
        unsubscribeFlag = false;
    }

}
