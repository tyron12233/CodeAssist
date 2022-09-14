package com.tyron.code.event;

import java.lang.ref.WeakReference;

public class SubscriptionReceipt<R extends Event> {

    private final Class<R> clazz;
    private final WeakReference<EventReceiver<R>> receiver;
    private final EventManager manager;

    public SubscriptionReceipt(Class<R> clazz,
                               EventReceiver<R> receiver,
                               EventManager manager) {
        this.clazz = clazz;
        this.receiver = new WeakReference<>(receiver);
        this.manager = manager;
    }

    public void unsubscribe() {
        EventManager.Receivers<R> receivers = manager.getReceivers(clazz);
        receivers.lock.writeLock().lock();
        try {
            EventReceiver<R> target = receiver.get();
            if (target != null) {
                receivers.receivers.remove(target);
            }
        } finally {
            receivers.lock.writeLock().unlock();
        }

    }
}
