package org.gradle.plugin.use.internal;

import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.artifacts.DependencyResolutionServices;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.internal.Factory;
import org.gradle.plugin.use.resolve.internal.ArtifactRepositoriesPluginResolver;
import org.gradle.plugin.use.resolve.internal.CompositePluginResolver;
import org.gradle.plugin.use.resolve.internal.CorePluginResolver;
import org.gradle.plugin.use.resolve.internal.NoopPluginResolver;
import org.gradle.plugin.use.resolve.internal.PluginRepositoriesProvider;
import org.gradle.plugin.use.resolve.internal.PluginResolver;
import org.gradle.plugin.use.resolve.internal.PluginResolverContributor;
import org.gradle.plugin.use.resolve.service.internal.ClientInjectedClasspathPluginResolver;
import org.gradle.plugin.use.resolve.service.internal.DefaultInjectedClasspathPluginResolver;

import java.util.LinkedList;
import java.util.List;

public class PluginResolverFactory implements Factory<PluginResolver> {

    private final PluginRegistry pluginRegistry;
    private final DocumentationRegistry documentationRegistry;
    private final ClientInjectedClasspathPluginResolver injectedClasspathPluginResolver;
    private final DependencyResolutionServices dependencyResolutionServices;
    private final PluginRepositoriesProvider pluginRepositoriesProvider;
    private final List<PluginResolverContributor> pluginResolverContributors;

    public PluginResolverFactory(
        PluginRegistry pluginRegistry,
        DocumentationRegistry documentationRegistry,
        ClientInjectedClasspathPluginResolver injectedClasspathPluginResolver,
        DependencyResolutionServices dependencyResolutionServices,
        PluginRepositoriesProvider pluginRepositoriesProvider,
        List<PluginResolverContributor> pluginResolverContributors
    ) {
        this.pluginRegistry = pluginRegistry;
        this.documentationRegistry = documentationRegistry;
        this.injectedClasspathPluginResolver = injectedClasspathPluginResolver;
        this.dependencyResolutionServices = dependencyResolutionServices;
        this.pluginRepositoriesProvider = pluginRepositoriesProvider;
        this.pluginResolverContributors = pluginResolverContributors;
    }

    @Override
    public PluginResolver create() {
        return new CompositePluginResolver(createDefaultResolvers());
    }

    private List<PluginResolver> createDefaultResolvers() {
        List<PluginResolver> resolvers = new LinkedList<>();
        addDefaultResolvers(resolvers);
        return resolvers;
    }

    /**
     * Returns the default PluginResolvers used by Gradle.
     * <p>
     * The plugins will be searched in a chain from the first to the last until a plugin is found.
     * So, order matters.
     * <p>
     * <ol>
     *     <li>{@link NoopPluginResolver} - Only used in tests.</li>
     *     <li>{@link CorePluginResolver} - distributed with Gradle</li>
     *     <li>{@link DefaultInjectedClasspathPluginResolver} - from a TestKit test's ClassPath</li>
     *     <li>Resolvers contributed by this distribution - plugins coming from included builds</li>
     *     <li>Resolvers based on the entries of the `pluginRepositories` block</li>
     *     <li>{@link org.gradle.plugin.use.resolve.internal.ArtifactRepositoriesPluginResolver} - from Gradle Plugin Portal if no `pluginRepositories` were defined</li>
     * </ol>
     * <p>
     * This order is optimized for both performance and to allow resolvers earlier in the order
     * to mask plugins which would have been found later in the order.
     */
    private void addDefaultResolvers(List<PluginResolver> resolvers) {
        resolvers.add(new NoopPluginResolver(pluginRegistry));
        resolvers.add(new CorePluginResolver(documentationRegistry, pluginRegistry));

        injectedClasspathPluginResolver.collectResolversInto(resolvers);

        pluginResolverContributors.forEach(contributor -> contributor.collectResolversInto(resolvers));
        resolvers.add(new ArtifactRepositoriesPluginResolver(dependencyResolutionServices, pluginRepositoriesProvider));
    }
}
