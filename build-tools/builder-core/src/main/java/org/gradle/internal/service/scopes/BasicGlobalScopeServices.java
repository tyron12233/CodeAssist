package org.gradle.internal.service.scopes;

import org.gradle.api.execution.internal.DefaultTaskInputsListeners;
import org.gradle.api.execution.internal.TaskInputsListeners;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.internal.Factory;
import org.gradle.internal.concurrent.DefaultExecutorFactory;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.api.internal.file.DefaultFileCollectionFactory;
import org.gradle.api.internal.file.DefaultFileLookup;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.api.internal.file.collections.DirectoryFileTree;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.internal.jvm.inspection.CachingJvmMetadataDetector;
import org.gradle.internal.jvm.inspection.DefaultJvmMetadataDetector;
import org.gradle.internal.jvm.inspection.DefaultJvmVersionDetector;
import org.gradle.internal.jvm.inspection.JvmMetadataDetector;
import org.gradle.internal.jvm.inspection.JvmVersionDetector;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.api.internal.provider.PropertyHost;
import org.gradle.internal.remote.services.MessagingServices;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.remote.internal.inet.InetAddressFactory;
import org.gradle.api.internal.tasks.DefaultTaskDependencyFactory;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.api.tasks.util.internal.PatternSets;
import org.gradle.api.tasks.util.internal.PatternSpecFactory;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.internal.DefaultFileLockManager;
import org.gradle.cache.internal.ProcessMetaDataProvider;
import org.gradle.cache.internal.locklistener.DefaultFileLockContentionHandler;
import org.gradle.cache.internal.locklistener.FileLockContentionHandler;
import org.gradle.process.internal.DefaultExecActionFactory;
import org.gradle.process.internal.ExecFactory;
import org.gradle.process.internal.ExecHandleFactory;

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
