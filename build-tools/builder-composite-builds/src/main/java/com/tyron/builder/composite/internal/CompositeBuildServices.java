package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.dsl.CapabilityNotationParserFactory;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.composite.CompositeBuildContext;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildFactory;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.internal.typeconversion.NotationParser;

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
//        public ValueSnapshotterSerializerRegistry createCompositeBuildsValueSnapshotterSerializerRegistry() {
//            return new CompositeBuildsValueSnapshotterSerializerRegistry();
//        }
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
