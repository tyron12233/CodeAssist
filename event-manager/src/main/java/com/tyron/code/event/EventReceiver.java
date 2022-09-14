package com.tyron.code.event;

public interface EventReceiver<T extends Event> {

    void onReceive(T event, Unsubscribe unsubscribe);
}
