package org.gradle.composite.internal;

import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParserFactory;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.composite.CompositeBuildContext;
import org.gradle.api.model.ObjectFactory;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.IncludedBuildFactory;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.internal.snapshot.impl.ValueSnapshotterSerializerRegistry;
import org.gradle.internal.typeconversion.NotationParser;

public class CompositeBuildServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.addProvider(new CompositeBuildSessionScopeServices());
    }

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.addProvider(new CompositeBuildTreeScopeServices());
    }

    private static class CompositeBuildSessionScopeServices {
        public ValueSnapshotterSerializerRegistry createCompositeBuildsValueSnapshotterSerializerRegistry() {
            return new CompositeBuildsValueSnapshotterSerializerRegistry();
        }
    }

    private static class CompositeBuildTreeScopeServices {
        public void configure(ServiceRegistration serviceRegistration) {
            serviceRegistration.add(BuildStateFactory.class);
            serviceRegistration.add(DefaultIncludedBuildFactory.class);
            serviceRegistration.add(DefaultIncludedBuildTaskGraph.class);
        }

        public BuildStateRegistry createIncludedBuildRegistry(CompositeBuildContext context,
                                                              Instantiator instantiator,
                                                              ListenerManager listenerManager,
                                                              ObjectFactory objectFactory,
                                                              NotationParser<Object, ComponentSelector> moduleSelectorNotationParser,
                                                              ImmutableAttributesFactory attributesFactory,
                                                              BuildStateFactory buildStateFactory,
                                                              IncludedBuildFactory includedBuildFactory) {
            NotationParser<Object, Capability> capabilityNotationParser = new CapabilityNotationParserFactory(false).create();
            IncludedBuildDependencySubstitutionsBuilder dependencySubstitutionsBuilder = new IncludedBuildDependencySubstitutionsBuilder(context, instantiator, objectFactory, attributesFactory, moduleSelectorNotationParser, capabilityNotationParser);
            return new DefaultIncludedBuildRegistry(includedBuildFactory, dependencySubstitutionsBuilder, listenerManager, buildStateFactory);
        }

        public CompositeBuildContext createCompositeBuildContext() {
            return new DefaultBuildableCompositeBuildContext();
        }

        public DefaultLocalComponentInAnotherBuildProvider createLocalComponentProvider() {
            return new DefaultLocalComponentInAnotherBuildProvider(new IncludedBuildDependencyMetadataBuilder());
        }
    }

    private static class CompositeBuildBuildScopeServices {
//        public ScriptClassPathInitializer createCompositeBuildClasspathResolver(BuildTreeWorkGraphController buildTreeWorkGraphController, BuildState currentBuild) {
//            return new CompositeBuildClassPathInitializer(buildTreeWorkGraphController, currentBuild);
//        }
//
//        public PluginResolverContributor createPluginResolver(BuildStateRegistry buildRegistry, BuildState consumingBuild, BuildIncluder buildIncluder) {
//            return new CompositeBuildPluginResolverContributor(buildRegistry, consumingBuild, buildIncluder);
//        }
    }
}
