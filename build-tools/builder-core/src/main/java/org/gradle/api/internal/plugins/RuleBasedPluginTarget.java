package org.gradle.api.internal.plugins;

import org.gradle.api.Plugin;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.configuration.ConfigurationTargetIdentifier;
import org.gradle.model.RuleSource;
import org.gradle.model.internal.inspect.ExtractedRuleSource;
import org.gradle.model.internal.inspect.ModelRuleExtractor;
import org.gradle.model.internal.inspect.ModelRuleSourceDetector;
import org.gradle.model.internal.registry.ModelRegistry;

import javax.annotation.Nullable;

public class RuleBasedPluginTarget implements PluginTarget {

    private final ProjectInternal target;
    private final PluginTarget imperativeTarget;
    private final ModelRuleExtractor ruleInspector;
    private final ModelRuleSourceDetector ruleDetector;

    public RuleBasedPluginTarget(ProjectInternal target, ModelRuleExtractor ruleInspector, ModelRuleSourceDetector ruleDetector) {
        this.target = target;
        this.ruleInspector = ruleInspector;
        this.ruleDetector = ruleDetector;
        this.imperativeTarget = new ImperativeOnlyPluginTarget<ProjectInternal>(target);
    }

    @Override
    public ConfigurationTargetIdentifier getConfigurationTargetIdentifier() {
        return imperativeTarget.getConfigurationTargetIdentifier();
    }

    @Override
    public void applyImperative(@Nullable String pluginId, Plugin<?> plugin) {
        imperativeTarget.applyImperative(pluginId, plugin);
    }

    @Override
    public void applyRules(@Nullable String pluginId, Class<?> clazz) {
        target.prepareForRuleBasedPlugins();
        ModelRegistry modelRegistry = target.getModelRegistry();
        Iterable<Class<? extends RuleSource>> declaredSources = ruleDetector.getDeclaredSources(clazz);
        for (Class<? extends RuleSource> ruleSource : declaredSources) {
            ExtractedRuleSource<?> rules = ruleInspector.extract(ruleSource);
            for (Class<?> dependency : rules.getRequiredPlugins()) {
                target.getPluginManager().apply(dependency);
            }
            modelRegistry.getRoot().applyToSelf(rules);
        }
    }

    @Override
    public void applyImperativeRulesHybrid(@Nullable String pluginId, Plugin<?> plugin) {
        applyImperative(pluginId, plugin);
        applyRules(pluginId, plugin.getClass());
    }

    @Override
    public String toString() {
        return target.toString();
    }
}
