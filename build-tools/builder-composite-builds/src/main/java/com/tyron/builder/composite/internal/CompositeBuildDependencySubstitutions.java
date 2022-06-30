package com.tyron.builder.composite.internal;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencySubstitution;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentSelector;
import com.tyron.builder.api.internal.artifacts.DependencySubstitutionInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionReasons;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.component.local.model.DefaultProjectComponentSelector;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Provides a dependency substitution rule for composite build,
 * that substitutes a project within the composite with any dependency with a matching ModuleIdentifier.
 */
public class CompositeBuildDependencySubstitutions implements Action<DependencySubstitution> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeBuildDependencySubstitutions.class);

    private final Multimap<ModuleIdentifier, ProjectComponentIdentifier> replacementMap = ArrayListMultimap.create();

    public CompositeBuildDependencySubstitutions(Collection<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> replacements) {
        for (Pair<ModuleVersionIdentifier, ProjectComponentIdentifier> replacement : replacements) {
            replacementMap.put(replacement.getLeft().getModule(), replacement.getRight());
        }
    }

    @Override
    public void execute(DependencySubstitution sub) {
        DependencySubstitutionInternal dependencySubstitution = (DependencySubstitutionInternal) sub;
        // Use the result of previous rules as the input for dependency substitution
        ComponentSelector requested = dependencySubstitution.getTarget();
        if (requested instanceof ModuleComponentSelector) {
            ModuleComponentSelector selector = (ModuleComponentSelector) requested;
            ProjectComponentIdentifier replacement = getReplacementFor(selector);
            if (replacement != null) {
                ProjectComponentSelector targetProject = DefaultProjectComponentSelector.newSelector(
                    replacement,
                    ((AttributeContainerInternal)requested.getAttributes()).asImmutable(),
                    requested.getRequestedCapabilities()
                );
                dependencySubstitution.useTarget(
                    targetProject,
                    ComponentSelectionReasons.COMPOSITE_BUILD);
            }
        }
    }

    private ProjectComponentIdentifier getReplacementFor(ModuleComponentSelector selector) {
        ModuleIdentifier candidateId = selector.getModuleIdentifier();
        Collection<ProjectComponentIdentifier> providingProjects = replacementMap.get(candidateId);
        if (providingProjects.isEmpty()) {
            LOGGER.debug("Found no composite build substitute for module '{}'.", candidateId);
            return null;
        }
        if (providingProjects.size() == 1) {
            ProjectComponentIdentifier match = providingProjects.iterator().next();
            LOGGER.info("Found project '{}' as substitute for module '{}'.", match, candidateId);
            return match;
        }
        throw new ModuleVersionResolveException(selector, () -> {
            SortedSet<String> sortedProjects =
                providingProjects.stream()
                .map(ComponentIdentifier::getDisplayName)
                .collect(Collectors.toCollection(TreeSet::new));
            return String.format("Module version '%s' is not unique in composite: can be provided by %s.", selector.getDisplayName(), sortedProjects);
        });
    }
}
