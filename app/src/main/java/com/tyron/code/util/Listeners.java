package com.tyron.code.util;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.function.Consumer;

public class Listeners {
    /**
     * Register a listener object with respect to its lifecycle owner
     * @param listener The listener object
     * @param lifecycleOwner The lifecycle object to listen to.
     * @param registerAction The consumer function to be called when this listener is registered.
     * @param unregisterAction The consumer function to be called when this listener is unregistered.
     * @param <T> The type of the listener.
     */
    public static <T> void registerListener(
            T listener,
            LifecycleOwner lifecycleOwner,
            Consumer<T> registerAction,
            Consumer<T> unregisterAction
    ) {
        registerAction.accept(listener);
        lifecycleOwner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                unregisterAction.accept(listener);
            }
        });
    }
}
