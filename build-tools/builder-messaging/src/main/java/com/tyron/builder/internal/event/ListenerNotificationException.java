package com.tyron.builder.internal.event;

import com.tyron.builder.internal.dispatch.MethodInvocation;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@code ListenerNotificationException} is thrown when a listener cannot be notified of an event.
 */
@Contextual
public class ListenerNotificationException extends DefaultMultiCauseException {
    private final MethodInvocation event;

    public ListenerNotificationException(@Nullable MethodInvocation event, String message, Iterable<? extends Throwable> causes) {
        super(message, causes);
        this.event = event;
    }

    public MethodInvocation getEvent() {
        return event;
    }
}