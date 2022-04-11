package com.tyron.builder.api.internal.event;

public class AnonymousListenerBroadcast<T> extends ListenerBroadcast<T> {
    public AnonymousListenerBroadcast(Class<T> type) {
        super(type);
    }
}