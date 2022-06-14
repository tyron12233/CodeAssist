package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.execution.internal.DefaultTaskInputsListeners;
import com.tyron.builder.api.execution.internal.TaskInputsListeners;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileLookup;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.internal.jvm.inspection.CachingJvmMetadataDetector;
import com.tyron.builder.internal.jvm.inspection.DefaultJvmMetadataDetector;
import com.tyron.builder.internal.jvm.inspection.DefaultJvmVersionDetector;
import com.tyron.builder.internal.jvm.inspection.JvmMetadataDetector;
import com.tyron.builder.internal.jvm.inspection.JvmVersionDetector;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.internal.remote.services.MessagingServices;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.remote.internal.inet.InetAddressFactory;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.internal.DefaultFileLockManager;
import com.tyron.builder.cache.internal.ProcessMetaDataProvider;
import com.tyron.builder.cache.internal.locklistener.DefaultFileLockContentionHandler;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;
import com.tyron.builder.process.internal.DefaultExecActionFactory;
import com.tyron.builder.process.internal.ExecFactory;
import com.tyron.builder.process.internal.ExecHandleFactory;

import java.io.File;

public class BasicGlobalScopeServices {

    void configure(ServiceRegistration serviceRegistration) {
        serviceRegistration.add(DefaultFileLookup.class);
        serviceRegistration.addProvider(new MessagingServices());
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

    JvmMetadataDetector createJvmMetadataDetector(ExecHandleFactory execHandleFactory, TemporaryFileProvider temporaryFileProvider) {
        return new CachingJvmMetadataDetector(new DefaultJvmMetadataDetector(execHandleFactory, temporaryFileProvider));
    }

    JvmVersionDetector createJvmVersionDetector(JvmMetadataDetector detector) {
        return new DefaultJvmVersionDetector(detector);
    }

    ExecFactory createExecFactory(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, ExecutorFactory executorFactory, TemporaryFileProvider temporaryFileProvider) {
        return DefaultExecActionFactory
                .of(fileResolver, fileCollectionFactory, executorFactory, temporaryFileProvider);
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

    TaskInputsListeners createTaskInputsListener(ListenerManager listenerManager) {
        return new DefaultTaskInputsListeners(listenerManager);
    }

    protected Factory<PatternSet> createPatternSetFactory(final PatternSpecFactory patternSpecFactory) {
        return PatternSets.getPatternSetFactory(patternSpecFactory);
    }

    DefaultListenerManager createListenerManager() {
        return new DefaultListenerManager(Scope.Global.class);
    }
}
