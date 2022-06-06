package com.tyron.builder.api.initialization.resolve;

import com.tyron.builder.api.Incubating;

/**
 * The rules mode determines how component metadata rules should be applied.
 *
 * @since 6.8
 */
@Incubating
public enum RulesMode {
    /**
     * If this mode is set, any component metadata rule declared on a project
     * will cause the project to use the rules declared by the project, ignoring
     * those declared in settings.
     *
     * This is the default behavior.
     */
    PREFER_PROJECT,

    /**
     * If this mode is set, any component metadata rule declared directly in a
     * project, either directly or via a plugin, will be ignored.
     */
    PREFER_SETTINGS,

    /**
     * If this mode is set, any component metadata rule declared directly in a
     * project, either directly or via a plugin, will trigger a build error.
     */
    FAIL_ON_PROJECT_RULES;
}
