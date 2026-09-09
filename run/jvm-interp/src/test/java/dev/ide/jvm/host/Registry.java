package dev.ide.jvm.host;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of androidx.lifecycle's {@code LifecycleRegistry}, as REAL (bridged) code: observers are registered
 * through a MARKER super-interface, dispatched only when the registered object is the single-method
 * sub-interface, and removed by equality. An interpreted lambda of the sub-interface must survive all three.
 */
public final class Registry {
    /** The marker super-interface ({@code LifecycleObserver}). */
    public interface Observer {}

    /** The single-method sub-interface ({@code LifecycleEventObserver}). */
    public interface EventObserver extends Observer {
        void onEvent(String event);
    }

    private final List<Object> registered = new ArrayList<>();
    /** Observers added that were not an {@link EventObserver}, so were never dispatched. */
    public int ignored;

    public void add(Observer observer) {
        if (observer instanceof EventObserver) registered.add(observer);
        else ignored++;
    }

    public void remove(Observer observer) {
        registered.remove(observer);
    }

    public int size() {
        return registered.size();
    }

    public void dispatch(String event) {
        for (Object o : new ArrayList<>(registered)) ((EventObserver) o).onEvent(event);
    }
}
