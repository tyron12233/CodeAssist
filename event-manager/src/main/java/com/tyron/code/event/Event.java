package com.tyron.code.event;

import androidx.annotation.NonNull;

import java.util.Objects;

import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.DoubleClickEvent;
import io.github.rosemoe.sora.event.EditorKeyEvent;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * An Event object describes an event of editor.
 * It includes several attributes such as time and the editor object.
 * Subclasses of Event will define their own fields or methods.
 *
 * @author Rosemoe
 */
public abstract class Event {

    private final long mEventTime;
    private boolean mIntercepted;

    public Event() {
        this(System.currentTimeMillis());
    }

    public Event(long eventTime) {
        mEventTime = eventTime;
        mIntercepted = false;
    }

    /**
     * Get event time
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Check whether this event can be intercepted (so that the event is not sent to other
     * receivers after being intercepted)
     * Intercept-able events:
     *
     * @see LongPressEvent
     * @see ClickEvent
     * @see DoubleClickEvent
     * @see EditorKeyEvent
     */
    public boolean canIntercept() {
        return false;
    }

    /**
     * Intercept the event.
     * <p>
     * Make sure {@link #canIntercept()} returns true. Otherwise, an
     * {@link UnsupportedOperationException}
     * will be thrown.
     */
    public void intercept() {
        if (!canIntercept()) {
            throw new UnsupportedOperationException("intercept() not supported");
        }
        mIntercepted = true;
    }

    /**
     * Check whether this event is intercepted
     */
    public boolean isIntercepted() {
        return mIntercepted;
    }

}
