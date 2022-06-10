package org.gradle.internal.model;

import org.gradle.api.Project;

/**
 * This listener is notified when a rule based plugin is applied to a project.
 * Listeners can react to this by activating compatibility layers that are otherwise not necessary.
 */
public interface RuleBasedPluginListener {

    void prepareForRuleBasedPlugins(Project project);
}
