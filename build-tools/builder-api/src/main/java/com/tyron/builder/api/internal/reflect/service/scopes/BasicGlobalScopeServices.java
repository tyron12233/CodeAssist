package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileLookup;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.remote.inet.InetAddressFactory;
import com.tyron.builder.api.internal.service.scopes.Scope;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.model.internal.DefaultObjectFactory;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.internal.DefaultFileLockManager;
import com.tyron.builder.cache.internal.ProcessMetaDataProvider;
import com.tyron.builder.cache.internal.locklistener.DefaultFileLockContentionHandler;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public class BasicGlobalScopeServices {

    void configure(ServiceRegistration serviceRegistration) {
        serviceRegistration.add(DefaultFileLookup.class);
//        serviceRegistration.addProvider(new MessagingServices());
    }

    InetAddressFactory createInetAddressFactory() {
        return new InetAddressFactory();
    }

    FileLockManager createFileLockManager(
            FileLockContentionHandler fileLockContentionHandler
    ) {
        return new DefaultFileLockManager(new ProcessMetaDataProvider() {
            @Override
            public String getProcessIdentifier() {
                return "TEST";
            }

            @Override
            public String getProcessDisplayName() {
                return "TEST";
            }
        }, fileLockContentionHandler);
    }

    DefaultFileLockContentionHandler createFileLockContentionHandler(ExecutorFactory executorFactory, InetAddressFactory inetAddressFactory) {
        return new DefaultFileLockContentionHandler(
                executorFactory,
                inetAddressFactory);
    }

    ExecutorFactory createExecutorFactory() {
        return new DefaultExecutorFactory();
    }

    DocumentationRegistry createDocumentationRegistry() {
        return new DocumentationRegistry();
    }

    PropertyHost createPropertyHost() {
        return PropertyHost.NO_OP;
    }

    FileResolver createFileResolver(FileLookup lookup) {
        return lookup.getFileResolver();
    }

    DirectoryFileTreeFactory createDirectoryTreeFileFactory(FileSystem fileSystem) {
        return new DirectoryFileTreeFactory() {
            @Override
            public DirectoryFileTree create(File directory) {
                return new DirectoryFileTree(directory, null, fileSystem);
            }

            @Override
            public DirectoryFileTree create(File directory, PatternSet patternSet) {
                return new DirectoryFileTree(directory, patternSet, fileSystem);
            }
        };
    }

    FileCollectionFactory createFileCollectionFactory(PathToFileResolver fileResolver, Factory<PatternSet> patternSetFactory, DirectoryFileTreeFactory directoryFileTreeFactory, PropertyHost propertyHost, FileSystem fileSystem) {
        return new DefaultFileCollectionFactory(fileResolver, DefaultTaskDependencyFactory.withNoAssociatedProject(), directoryFileTreeFactory, patternSetFactory, propertyHost, fileSystem);
    }

    PatternSpecFactory createPatternSpecFactory() {
        return PatternSpecFactory.INSTANCE;
    }

    protected Factory<PatternSet> createPatternSetFactory(final PatternSpecFactory patternSpecFactory) {
        return PatternSets.getPatternSetFactory(patternSpecFactory);
    }

    DefaultListenerManager createListenerManager() {
        return new DefaultListenerManager(Scope.Global.class);
    }
}
