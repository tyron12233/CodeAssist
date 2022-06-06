package com.tyron.builder.process.internal.shutdown;

import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ShutdownHooks {
    private static final Logger LOGGER = Logging.getLogger(ShutdownHooks.class);
    private static final Map<Runnable, Thread> HOOKS = new ConcurrentHashMap<Runnable, Thread>();

    public static void addShutdownHook(Runnable shutdownHook) {
        Thread thread = new Thread(shutdownHook, "gradle-shutdown-hook");
        HOOKS.put(shutdownHook, thread);
        Runtime.getRuntime().addShutdownHook(thread);
    }

    public static void removeShutdownHook(Runnable shutdownHook) {
        try {
            Thread thread = HOOKS.remove(shutdownHook);
            if (thread != null) {
                Runtime.getRuntime().removeShutdownHook(thread);
            }
        } catch (IllegalStateException e) {
            // When shutting down is in progress, invocation of this method throws exception,
            // interrupting other shutdown hooks, so we catch it here.
            //
            // Caused by: java.lang.IllegalStateException: Shutdown in progress
            //        at java.base/java.lang.ApplicationShutdownHooks.remove(ApplicationShutdownHooks.java:82)
            //        at java.base/java.lang.Runtime.removeShutdownHook(Runtime.java:243)
            LOGGER.error("Remove shutdown hook failed", e);
        }
    }
}
