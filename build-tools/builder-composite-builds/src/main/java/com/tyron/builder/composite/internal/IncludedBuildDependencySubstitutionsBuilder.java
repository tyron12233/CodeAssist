package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution.DefaultDependencySubstitutions;
import com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionsInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.composite.CompositeBuildContext;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.build.CompositeBuildParticipantBuildState;
import com.tyron.builder.internal.build.IncludedBuildState;
import com.tyron.builder.internal.typeconversion.NotationParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class IncludedBuildDependencySubstitutionsBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(IncludedBuildDependencySubstitutionsBuilder.class);

    private final CompositeBuildContext context;
    private final Instantiator instantiator;
    private final ObjectFactory objectFactory;
    private final ImmutableAttributesFactory attributesFactory;
    private final NotationParser<Object, ComponentSelector> moduleSelectorNotationParser;
    private final NotationParser<Object, Capability> capabilitiesParser;
    private final Set<IncludedBuildState> processed = new HashSet<>();

    public IncludedBuildDependencySubstitutionsBuilder(CompositeBuildContext context,
                                                       Instantiator instantiator,
                                                       ObjectFactory objectFactory,
                                                       ImmutableAttributesFactory attributesFactory,
                                                       NotationParser<Object, ComponentSelector> moduleSelectorNotationParser,
                                                       NotationParser<Object, Capability> capabilitiesParser) {
        this.context = context;
        this.instantiator = instantiator;
        this.objectFactory = objectFactory;
        this.attributesFactory = attributesFactory;
        this.moduleSelectorNotationParser = moduleSelectorNotationParser;
        this.capabilitiesParser = capabilitiesParser;
    }

    public void build(IncludedBuildState build) {
        if (processed.contains(build)) {
            // This may happen during early resolution, where we iterate through all builds to find only
            // the ones for which we need to register substitutions early so that they are available
            // during plugin application from plugin builds.
            // See: DefaultIncludedBuildRegistry.ensureConfigured()
            return;
        }
        processed.add(build);
        DependencySubstitutionsInternal substitutions = resolveDependencySubstitutions(build);
        if (!substitutions.rulesMayAddProjectDependency()) {
            context.addAvailableModules(build.getAvailableModules());
        } else {
            // Register the defined substitutions for included build
            context.registerSubstitution(substitutions.getRuleAction());
        }
    }

    public void build(CompositeBuildParticipantBuildState rootBuildState) {
        context.addAvailableModules(rootBuildState.getAvailableModules());
    }

    private DependencySubstitutionsInternal resolveDependencySubstitutions(IncludedBuildState build) {
        DependencySubstitutionsInternal dependencySubstitutions = DefaultDependencySubstitutions
                .forIncludedBuild(build, instantiator, objectFactory, attributesFactory, moduleSelectorNotationParser, capabilitiesParser);
        build.getRegisteredDependencySubstitutions().execute(dependencySubstitutions);
        return dependencySubstitutions;
    }
}
