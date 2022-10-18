package com.tyron.builder.plugin.builder;

import java.util.List;
/**
 * Options for aapt.
 */
public interface AaptOptions {
    /**
     * Returns the value for the --ignore-assets option, or null
     */
    String getIgnoreAssets();
    /**
     * Returns the list of values for the -0 (disabled compression) option, or null
     */
    List<String> getNoCompress();
}