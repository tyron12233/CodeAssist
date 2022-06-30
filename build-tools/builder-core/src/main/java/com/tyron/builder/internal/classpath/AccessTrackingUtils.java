package com.tyron.builder.internal.classpath;

import com.google.common.collect.Maps;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

class AccessTrackingUtils {
    private AccessTrackingUtils() {
    }

    /**
     * Tries to convert the given Object (typically the argument of {@link Collection#contains(Object)})
     * to {@code Map.Entry<String, String>}. The conversion succeeds if the Object is an {@code Entry}
     * and both {@code getKey()} and {@code getValue()} return non-{@code null} {@code String}s. The
     * intended use case is the tracking of {@code entrySet()} operations in the instrumented results
     * of {@code System.getProperties()} and {@code System.getenv()} for configuration cache. This use
     * case makes tracking of non-entries and entries with anything but strings redundant because only
     * the build script can sneak in such {@code Entry} and change the result of e.g. {@code contains}
     * call. The external world cannot influence the result, so it makes no sense to record such access
     * as an input to the configuration cache.
     * <p>
     * Note that the returned object doesn't have to be the same object that was passed to the method.
     *
     * @param o the object to cast
     * @return the entry if the object is {@code Map.Entry<String, String>} or {@code null} otherwise
     */
    @Nullable
    public static Map.Entry<String, String> tryConvertingToTrackableEntry(Object o) {
        if (!(o instanceof Map.Entry)) {
            return null;
        }
        Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
        // Return a copy to make sure that the results of getKey() and getValue() do not change.
        Object key = entry.getKey();
        Object value = entry.getValue();
        if (key instanceof String && value instanceof String) {
            return Maps.immutableEntry((String) key, (String) value);
        }
        return null;
    }

}
