package com.tyron.builder.api.artifacts;

import java.util.Map;
import java.util.Set;

/**
 * <p>A container for adding exclude rules for dependencies.</p>
 */
public interface ExcludeRuleContainer {
    /**
     * Returns all the exclude rules added to this container. If no exclude rules has been added an empty list is
     * returned.
     */
    Set<ExcludeRule> getRules();

    /**
     * Adds an exclude rule to this container. The ExcludeRule object gets created internally based on the map values
     * passed to this method. The possible keys for the map are:
     *
     * <ul>
     * <li><code>group</code> - The exact name of the organization or group that should be excluded.
     * <li><code>module</code> - The exact name of the module that should be excluded
     * </ul>
     *
     * @param args A map describing the exclude pattern.
     */
    void add(Map<String, String> args);
}
