package com.tyron.builder.plugin.use.internal;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerInternal;
import com.tyron.builder.api.internal.plugins.ClassloaderBackedPluginDescriptorLocator;
import com.tyron.builder.api.internal.plugins.PluginDescriptorLocator;
import com.tyron.builder.api.internal.plugins.PluginImplementation;
import com.tyron.builder.api.internal.plugins.PluginInspector;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.api.internal.plugins.PluginRegistry;
import com.tyron.builder.api.plugins.InvalidPluginException;
import com.tyron.builder.api.plugins.UnknownPluginException;
import com.tyron.builder.internal.classpath.CachedClasspathTransformer;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.exceptions.LocationAwareException;
import com.tyron.builder.plugin.management.internal.PluginRequestInternal;
import com.tyron.builder.plugin.management.internal.PluginRequests;
import com.tyron.builder.plugin.management.internal.PluginResolutionStrategyInternal;
import com.tyron.builder.plugin.use.PluginId;
import com.tyron.builder.plugin.use.resolve.internal.AlreadyOnClasspathPluginResolver;
import com.tyron.builder.plugin.use.resolve.internal.PluginRepositoriesProvider;
import com.tyron.builder.plugin.use.resolve.internal.PluginResolution;
import com.tyron.builder.plugin.use.resolve.internal.PluginResolutionResult;
import com.tyron.builder.plugin.use.resolve.internal.PluginResolveContext;
import com.tyron.builder.plugin.use.resolve.internal.PluginResolver;
import com.tyron.builder.util.internal.TextUtil;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Maps.newLinkedHashMap;
import static com.tyron.builder.internal.classpath.CachedClasspathTransformer.StandardTransform.BuildLogic;
import static com.tyron.builder.util.internal.CollectionUtils.collect;

public class DefaultPluginRequestApplicator implements PluginRequestApplicator {
    private final PluginRegistry pluginRegistry;
    private final PluginResolverFactory pluginResolverFactory;
    private final PluginRepositoriesProvider pluginRepositoriesProvider;
    private final PluginResolutionStrategyInternal pluginResolutionStrategy;
    private final PluginInspector pluginInspector;
    private final CachedClasspathTransformer cachedClasspathTransformer;

    public DefaultPluginRequestApplicator(PluginRegistry pluginRegistry, PluginResolverFactory pluginResolver, PluginRepositoriesProvider pluginRepositoriesProvider, PluginResolutionStrategyInternal pluginResolutionStrategy, PluginInspector pluginInspector, CachedClasspathTransformer cachedClasspathTransformer) {
        this.pluginRegistry = pluginRegistry;
        this.pluginResolverFactory = pluginResolver;
        this.pluginRepositoriesProvider = pluginRepositoriesProvider;
        this.pluginResolutionStrategy = pluginResolutionStrategy;
        this.pluginInspector = pluginInspector;
        this.cachedClasspathTransformer = cachedClasspathTransformer;
    }

    @Override
    public void applyPlugins(final PluginRequests requests, final ScriptHandlerInternal scriptHandler, @Nullable final PluginManagerInternal target, final ClassLoaderScope classLoaderScope) {
        if (target == null || requests.isEmpty()) {
            defineScriptHandlerClassScope(scriptHandler, classLoaderScope, Collections.emptyList());
            return;
        }

        // This should move to settings finalization
        pluginRepositoriesProvider.prepareForPluginResolution();

        final PluginResolver effectivePluginResolver = wrapInAlreadyInClasspathResolver(classLoaderScope);
        if (!requests.isEmpty()) {
            addPluginArtifactRepositories(scriptHandler.getRepositories());
        }
        List<Result> results = resolvePluginRequests(requests, effectivePluginResolver);

        final List<Consumer<PluginManagerInternal>> pluginApplyActions = newLinkedList();
        final Map<Result, PluginImplementation<?>> pluginImplsFromOtherLoaders = newLinkedHashMap();

        if (!results.isEmpty()) {
            for (final Result result : results) {
                applyPlugin(result.request, result.found.getPluginId(), new Runnable() {
                    @Override
                    public void run() {
                        result.found.execute(new PluginResolveContext() {
                            @Override
                            public void addLegacy(PluginId pluginId, Object dependencyNotation) {
                                pluginApplyActions.add(target -> applyLegacyPlugin(target, result, pluginId));
                                scriptHandler.addScriptClassPathDependency(dependencyNotation);
                            }

                            @Override
                            public void add(PluginImplementation<?> plugin) {
                                pluginApplyActions.add(target -> applyPlugin(target, result, plugin));
                            }

                            @Override
                            public void addFromDifferentLoader(PluginImplementation<?> plugin) {
                                pluginApplyActions.add(target -> applyPlugin(target, result, plugin));
                                pluginImplsFromOtherLoaders.put(result, plugin);
                            }
                        });
                    }
                });
            }
        }

        defineScriptHandlerClassScope(scriptHandler, classLoaderScope, pluginImplsFromOtherLoaders.values());
        pluginApplyActions.forEach(pluginApplyAction -> pluginApplyAction.accept(target));
    }

    private void applyPlugin(PluginManagerInternal target, Result result, PluginImplementation<?> impl) {
        applyPlugin(result.request, result.found.getPluginId(), () -> {
            if (result.request.isApply()) {
                target.apply(impl);
            }
        });
    }

    // We're making an assumption here that the target's plugin registry is backed classLoaderScope.
    // Because we are only build.gradle files right now, this holds.
    // It won't for arbitrary scripts though.
    private void applyLegacyPlugin(PluginManagerInternal target, Result result, PluginId id) {
        applyPlugin(result.request, id, () -> {
            if (result.request.isApply()) {
                target.apply(id.toString());
            }
        });
    }

    private List<Result> resolvePluginRequests(PluginRequests requests, PluginResolver effectivePluginResolver) {
        return collect(requests, request -> {
            PluginRequestInternal configuredRequest = pluginResolutionStrategy.applyTo(request);
            return resolveToFoundResult(effectivePluginResolver, configuredRequest);
        });
    }

    private void addPluginArtifactRepositories(RepositoryHandler repositories) {
        if (pluginRepositoriesProvider.isExclusiveContentInUse() && !repositories.isEmpty()) {
            throw new InvalidUserCodeException("When using exclusive repository content in 'settings.pluginManagement.repositories', you cannot add repositories to 'buildscript.repositories'.\n" +
                "See the documentation in " + new DocumentationRegistry().getDocumentationFor("declaring_repositories", "declaring_content_exclusively_found_in_one_repository") + ".");
        }
        repositories.addAll(pluginRepositoriesProvider.getPluginRepositories());
    }

    private void defineScriptHandlerClassScope(ScriptHandlerInternal scriptHandler, ClassLoaderScope classLoaderScope, Iterable<PluginImplementation<?>> pluginsFromOtherLoaders) {
        exportBuildLogicClassPathTo(classLoaderScope, scriptHandler.getNonInstrumentedScriptClassPath());

        for (PluginImplementation<?> pluginImplementation : pluginsFromOtherLoaders) {
            classLoaderScope.export(pluginImplementation.asClass().getClassLoader());
        }

        classLoaderScope.lock();
    }

    private void exportBuildLogicClassPathTo(ClassLoaderScope classLoaderScope, ClassPath classPath) {
        ClassPath cachedClassPath = cachedClasspathTransformer.transform(classPath, BuildLogic);
        classLoaderScope.export(cachedClassPath);
    }

    private PluginResolver wrapInAlreadyInClasspathResolver(ClassLoaderScope classLoaderScope) {
        ClassLoaderScope parentLoaderScope = classLoaderScope.getParent();
        PluginDescriptorLocator scriptClasspathPluginDescriptorLocator = new ClassloaderBackedPluginDescriptorLocator(parentLoaderScope.getExportClassLoader());
        PluginResolver pluginResolver = pluginResolverFactory.create();
        return new AlreadyOnClasspathPluginResolver(pluginResolver, pluginRegistry, parentLoaderScope, scriptClasspathPluginDescriptorLocator, pluginInspector);
    }

    private void applyPlugin(PluginRequestInternal request, PluginId id, Runnable applicator) {
        try {
            try {
                applicator.run();
            } catch (UnknownPluginException e) {
                throw couldNotApply(request, id, e);
            } catch (Exception e) {
                throw exceptionOccurred(request, e);
            }
        } catch (Exception e) {
            throw new LocationAwareException(e, request.getScriptDisplayName(), request.getLineNumber());
        }
    }

    private InvalidPluginException couldNotApply(PluginRequestInternal request, PluginId id, UnknownPluginException cause) {
        return new InvalidPluginException(
            String.format(
                "Could not apply requested plugin %s as it does not provide a plugin with id '%s'."
                    + " This is caused by an incorrect plugin implementation."
                    + " Please contact the plugin author(s).",
                request, id),
            cause);
    }

    private InvalidPluginException exceptionOccurred(PluginRequestInternal request, Exception e) {
        return new InvalidPluginException(String.format("An exception occurred applying plugin request %s", request), e);
    }

    private Result resolveToFoundResult(PluginResolver effectivePluginResolver, PluginRequestInternal request) {
        Result result = new Result(request);
        try {
            effectivePluginResolver.resolve(request, result);
        } catch (Exception e) {
            throw new LocationAwareException(
                new BuildException(String.format("Error resolving plugin %s", request.getDisplayName()), e),
                request.getScriptDisplayName(), request.getLineNumber());
        }

        if (!result.isFound()) {
            String message = buildNotFoundMessage(request, result);
            Exception exception = new UnknownPluginException(message);
            throw new LocationAwareException(exception, request.getScriptDisplayName(), request.getLineNumber());
        }

        return result;
    }

    private String buildNotFoundMessage(PluginRequestInternal pluginRequest, Result result) {
        if (result.notFoundList.isEmpty()) {
            // this shouldn't happen, resolvers should call notFound()
            return String.format("Plugin %s was not found", pluginRequest.getDisplayName());
        } else {
            Formatter sb = new Formatter();
            sb.format("Plugin %s was not found in any of the following sources:%n", pluginRequest.getDisplayName());

            for (NotFound notFound : result.notFoundList) {
                sb.format("%n- %s (%s)", notFound.source, notFound.message);
                if (notFound.detail != null) {
                    sb.format("%n%s", TextUtil.indent(notFound.detail, "  "));
                }
            }

            return sb.toString();
        }
    }

    private static class NotFound {
        private final String source;
        private final String message;
        private final String detail;

        private NotFound(String source, String message, @Nullable String detail) {
            this.source = source;
            this.message = message;
            this.detail = detail;
        }
    }

    private static class Result implements PluginResolutionResult {
        private final List<NotFound> notFoundList = new LinkedList<>();
        private final PluginRequestInternal request;
        private PluginResolution found;

        public Result(PluginRequestInternal request) {
            this.request = request;
        }

        @Override
        public void notFound(String sourceDescription, String notFoundMessage) {
            notFoundList.add(new NotFound(sourceDescription, notFoundMessage, null));
        }

        @Override
        public void notFound(String sourceDescription, String notFoundMessage, String notFoundDetail) {
            notFoundList.add(new NotFound(sourceDescription, notFoundMessage, notFoundDetail));
        }

        @Override
        public void found(String sourceDescription, PluginResolution pluginResolution) {
            found = pluginResolution;
        }

        @Override
        public boolean isFound() {
            return found != null;
        }
    }
}
