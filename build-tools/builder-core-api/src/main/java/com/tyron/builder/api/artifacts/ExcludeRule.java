package com.tyron.builder.api.artifacts;

/**
 * An {@code ExcludeRule} is used to describe transitive dependencies that should be excluded when resolving
 * dependencies.
 */
public interface ExcludeRule {
    String GROUP_KEY = "group";
    String MODULE_KEY = "module";

    /**
     * The exact name of the organization or group that should be excluded.
      */
    String getGroup();

    /**
     * The exact name of the module that should be excluded.
     */
    String getModule();
}
