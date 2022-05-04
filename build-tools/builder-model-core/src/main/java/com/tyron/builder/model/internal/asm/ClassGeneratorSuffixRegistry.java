package com.tyron.builder.model.internal.asm;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClassGeneratorSuffixRegistry {
    // Use Throwable to record the location where a suffix was registered, to allow diagnostics when a collision is found
    // This may have performance implications, however the assumption is that class generators are global scoped objects that are created once and in very small numbers
    private static final Map<String, Throwable> SUFFIXES = new ConcurrentHashMap<>();

    /**
     * Registers the given suffix as in use for generated class names.
     */
    public static String register(String suffix) {
        Throwable previous = SUFFIXES.putIfAbsent(suffix, markerForSuffix(suffix));
        if (previous != null) {
            throw new IllegalStateException("A class generator with suffix '" + suffix + "' is already registered.", previous);
        }
        return suffix;
    }

    /**
     * Assigns a suffix to use for generated class names.
     */
    public static String assign(String suffix) {
        if (SUFFIXES.putIfAbsent(suffix, markerForSuffix(suffix)) == null) {
            return suffix;
        }
        int index = 1;
        while (true) {
            String suffixWithIndex = suffix + index;
            if (SUFFIXES.putIfAbsent(suffixWithIndex, markerForSuffix(suffixWithIndex)) == null) {
                return suffixWithIndex;
            }
            index++;
        }
    }

    @NotNull
    private static RuntimeException markerForSuffix(String suffix) {
        return new RuntimeException("Class generated with suffix '" + suffix + "' registered.");
    }
}
