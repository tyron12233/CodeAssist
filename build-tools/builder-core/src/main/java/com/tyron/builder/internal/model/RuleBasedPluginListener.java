package com.tyron.builder.internal.model;

import com.tyron.builder.api.BuildProject;

/**
 * This listener is notified when a rule based plugin is applied to a project.
 * Listeners can react to this by activating compatibility layers that are otherwise not necessary.
 */
public interface RuleBasedPluginListener {

    void prepareForRuleBasedPlugins(BuildProject project);
}
