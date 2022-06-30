package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.artifacts.DependencyManagementServices;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.UnknownProjectFinder;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.groovy.scripts.ScriptSource;

public class DefaultScriptHandlerFactory implements ScriptHandlerFactory {
    private final DependencyManagementServices dependencyManagementServices;
    private final FileCollectionFactory fileCollectionFactory;
    private final DependencyMetaDataProvider dependencyMetaDataProvider;
    private final ScriptClassPathResolver scriptClassPathResolver;
    private final NamedObjectInstantiator instantiator;
    private final FileResolver fileResolver;
    private final ProjectFinder projectFinder = new UnknownProjectFinder("Cannot use project dependencies in a script classpath definition.");

    public DefaultScriptHandlerFactory(
            DependencyManagementServices dependencyManagementServices,
            FileResolver fileResolver,
            FileCollectionFactory fileCollectionFactory,
            DependencyMetaDataProvider dependencyMetaDataProvider,
            ScriptClassPathResolver scriptClassPathResolver,
            NamedObjectInstantiator instantiator
    ) {
        this.dependencyManagementServices = dependencyManagementServices;
        this.fileResolver = fileResolver;
        this.fileCollectionFactory = fileCollectionFactory;
        this.dependencyMetaDataProvider = dependencyMetaDataProvider;
        this.scriptClassPathResolver = scriptClassPathResolver;
        this.instantiator = instantiator;
    }

    @Override
    public ScriptHandlerInternal create(ScriptSource scriptSource, ClassLoaderScope classLoaderScope) {
        return create(scriptSource, classLoaderScope, RootScriptDomainObjectContext.INSTANCE);
    }

    @Override
    public ScriptHandlerInternal create(ScriptSource scriptSource, ClassLoaderScope classLoaderScope, DomainObjectContext context) {
        DependencyResolutionServices
                services = dependencyManagementServices.create(fileResolver, fileCollectionFactory, dependencyMetaDataProvider, projectFinder, context);
        return new DefaultScriptHandler(scriptSource, services, classLoaderScope, scriptClassPathResolver, instantiator);
    }

}
