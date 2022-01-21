package com.tyron.completion.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * An interface for calculating text edits in the editor.
 * @param <T> The class needed for this rewrite to calculate its edits.
 */
public interface Rewrite<T> {

    /**
     * Convenience field to use when a rewrite has been cancelled
     */
    Map<Path, TextEdit[]> CANCELLED = Collections.emptyMap();

    /**
     * Calculate the rewrites needed to a file
     * @param t The needed class to calculate this rewrite
     * @return map of path and the text edits that should be applied on that file.
     */
    Map<Path, TextEdit[]> rewrite(T t);
}
