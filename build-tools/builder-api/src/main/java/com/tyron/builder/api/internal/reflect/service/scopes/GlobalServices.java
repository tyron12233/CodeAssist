package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.PathValidation;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.execution.steps.WorkInputListeners;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.DefaultFileOperations;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.DeleteSpec;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.impl.DefaultFileMetadata;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.hash.DefaultFileHasher;
import com.tyron.builder.api.internal.hash.FileHasher;
import com.tyron.builder.api.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.operations.BuildOperationListener;
import com.tyron.builder.api.internal.operations.BuildOperationListenerManager;
import com.tyron.builder.api.internal.operations.BuildOperationProgressEventEmitter;
import com.tyron.builder.api.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationListenerManager;
import com.tyron.builder.api.internal.os.OperatingSystem;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.service.scopes.DefaultWorkInputListeners;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.snapshot.impl.DirectorySnapshotterStatistics;
import com.tyron.builder.api.internal.time.Clock;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.model.internal.DefaultObjectFactory;
import com.tyron.builder.api.providers.ProviderFactory;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.options.Option;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;
import com.tyron.builder.internal.service.DefaultServiceLocator;
import com.tyron.builder.internal.service.scopes.DefaultGradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
import com.tyron.builder.internal.service.scopes.WorkerSharedGlobalScopeServices;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.DefaultFileSystemAccess;
import com.tyron.builder.internal.vfs.impl.DefaultSnapshotHierarchy;
import com.tyron.builder.internal.vfs.impl.VfsRootReference;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistryFactory;
import com.tyron.builder.internal.watch.registry.impl.WindowsFileWatcherRegistryFactory;
import com.tyron.builder.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.FileChangeListeners;
import com.tyron.builder.internal.watch.vfs.WatchableFileSystemDetector;
import com.tyron.builder.internal.watch.vfs.impl.DefaultWatchableFileSystemDetector;
import com.tyron.builder.internal.watch.vfs.impl.LocationsWrittenByCurrentBuild;
import com.tyron.builder.internal.watch.vfs.impl.WatchingNotSupportedVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.impl.WatchingVirtualFileSystem;

import net.rubygrapefruit.platform.file.FileSystems;
import net.rubygrapefruit.platform.internal.PosixFileSystems;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class GlobalServices extends WorkerSharedGlobalScopeServices {

    public GlobalServices() {
        super();
    }

    void configure(ServiceRegistration registration, List<String> somethingEmpty) {
        final List<PluginServiceRegistry> pluginServiceFactories = new DefaultServiceLocator(getClass().getClassLoader()).getAll(PluginServiceRegistry.class);
        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceFactories) {
            registration.add(PluginServiceRegistry.class, pluginServiceRegistry);
            pluginServiceRegistry.registerGlobalServices(registration);
        }
        registration.add(BuildLayoutFactory.class);

        registration.addProvider(new ExecutionGlobalServices());
    }

    BuildOperationProgressEventEmitter createBuildOperationProgressEventEmitter(
            Clock clock,
            CurrentBuildOperationRef currentBuildOperationRef,
            BuildOperationListenerManager listenerManager
    ) {
        return new BuildOperationProgressEventEmitter(
                clock,
                currentBuildOperationRef,
                listenerManager.getBroadcaster()
        );
    }

    GradleUserHomeScopeServiceRegistry createGradleUserHomeScopeServiceRegistry(ServiceRegistry globalServices) {
        return new DefaultGradleUserHomeScopeServiceRegistry(globalServices, new GradleUserHomeScopeServices(globalServices));
    }

    CurrentBuildOperationRef createCurrentBuildOperationRef() {
        return CurrentBuildOperationRef.instance();
    }

    BuildOperationListenerManager createBuildOperationListenerManager() {
        return new DefaultBuildOperationListenerManager();
    }

    WorkInputListeners createWorkInputListeners(
            ListenerManager listenerManager
    ) {
        return new DefaultWorkInputListeners(listenerManager);
    }


    BuildOperationListener createBuildOperationListener(
            ListenerManager listenerManager
    ) {
        return listenerManager.getBroadcaster(BuildOperationListener.class);
    }

    FileOperations createFileOperations(
            FileResolver fileResolver,
            DirectoryFileTreeFactory directoryFileTreeFactory,
            StreamHasher streamHasher,
            FileHasher fileHasher,
            FileCollectionFactory fileCollectionFactory,
            ObjectFactory objectFactory,
            FileSystem fileSystem,
            Factory<PatternSet> patternSetFactory,
            Deleter deleter,
            DocumentationRegistry documentationRegistry,
            ProviderFactory providerFactory
    ) {
        return new DefaultFileOperations(
                fileResolver,
//                temporaryFileProvider,
                DirectInstantiator.INSTANCE,
                directoryFileTreeFactory,
                streamHasher,
                fileHasher,
                fileCollectionFactory,
                objectFactory,
                fileSystem,
                patternSetFactory,
                deleter,
                documentationRegistry,
                providerFactory
        );
    }

    ProviderFactory createProviderFactory() {
        return new ProviderFactory();
    }


    FileHasher createFileHasher(
            StreamHasher streamHasher
    ) {
        return new DefaultFileHasher(streamHasher);
    }

    FileChangeListeners createFileChangeListeners(ListenerManager listenerManager) {
        return new DefaultFileChangeListeners(listenerManager);
    }

    LocationsWrittenByCurrentBuild createLocationsUpdatedByCurrentBuild(ListenerManager listenerManager) {
        LocationsWrittenByCurrentBuild locationsWrittenByCurrentBuild = new LocationsWrittenByCurrentBuild();
//        listenerManager.addListener(new RootBuildLifecycleListener() {
//            @Override
//            public void afterStart() {
//                locationsWrittenByCurrentBuild.buildStarted();
//            }
//
//            @Override
//            public void beforeComplete() {
//                locationsWrittenByCurrentBuild.buildFinished();
//            }
//        });
        return locationsWrittenByCurrentBuild;
    }

    FileSystems createFileSystems() {
        return new PosixFileSystems();
    }

    WatchableFileSystemDetector createWatchableFileSystemDetector(FileSystems fileSystems) {
        return new DefaultWatchableFileSystemDetector(fileSystems);
    }

    VirtualFileSystem createVirtualFileSystem(
            LocationsWrittenByCurrentBuild locationsWrittenByCurrentBuild,
            ListenerManager listenerManager,
            FileChangeListeners fileChangeListeners,
            FileSystem fileSystem,
            WatchableFileSystemDetector watchableFileSystemDetector
    ) {
        VfsRootReference reference = new VfsRootReference(DefaultSnapshotHierarchy.empty(CaseSensitivity.CASE_SENSITIVE));
        BuildLifecycleAwareVirtualFileSystem virtualFileSystem = determineWatcherRegistryFactory(
                OperatingSystem.current(),
                path -> false)
                .<BuildLifecycleAwareVirtualFileSystem>map(watcherRegistryFactory -> new WatchingVirtualFileSystem(
                        watcherRegistryFactory,
                        reference,
                        sectionId -> null,
                        locationsWrittenByCurrentBuild,
                        watchableFileSystemDetector,
                        fileChangeListeners
                ))
                .orElse(new WatchingNotSupportedVirtualFileSystem(reference));
//        listenerManager.addListener);
        return virtualFileSystem;
    }

    private Optional<FileWatcherRegistryFactory> determineWatcherRegistryFactory(
            OperatingSystem operatingSystem,
            Predicate<String> watchingFilter
    ) {
        if (operatingSystem.isWindows()) {
            return Optional.of(new WindowsFileWatcherRegistryFactory(watchingFilter));
        }
        return Optional.empty();
    }

    FileSystem createFileSystem() {
        return new FileSystem() {
            @Override
            public boolean isCaseSensitive() {
                return true;
            }

            @Override
            public boolean canCreateSymbolicLink() {
                return false;
            }

            @Override
            public void createSymbolicLink(File link, File target) throws FileException {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isSymlink(File suspect) {
                return false;
            }

            @Override
            public void chmod(File file, int mode) throws FileException {

            }

            @Override
            public int getUnixMode(File f) throws FileException {
                return 0;
            }

            @Override
            public FileMetadata stat(File f) throws FileException {
                if (!f.exists()) {
                    return DefaultFileMetadata.missing(FileMetadata.AccessType.DIRECT);
                }
                if (f.isDirectory()) {
                    return DefaultFileMetadata.directory(FileMetadata.AccessType.DIRECT);
                }
                return DefaultFileMetadata.file(f.lastModified(), f.length(), FileMetadata.AccessType.DIRECT);
            }
        };
    }

    StringInterner createStringInterner() {
        return new StringInterner();
    }

    FileSystemAccess createFileSystemAccess(
            FileHasher fileHasher,
            StringInterner interner,
            Stat stat,
            VirtualFileSystem virtualFileSystem
    ) {
        return new DefaultFileSystemAccess(fileHasher, interner, stat, virtualFileSystem,
                new FileSystemAccess.WriteListener() {
                    @Override
                    public void locationsWritten(Iterable<String> locations) {

                    }
                }, new DirectorySnapshotterStatistics.Collector());
    }

    ObjectFactory createObjectFactory(
            FileCollectionFactory fileCollectionFactory,
            FilePropertyFactory filePropertyFactory,
            PropertyFactory propertyFactory
    ) {
        return new DefaultObjectFactory(fileCollectionFactory, filePropertyFactory, propertyFactory);
    }

    FilePropertyFactory createFilePropertyFactory(
            PropertyHost propertyHost,
            FileResolver fileResolver,
            FileCollectionFactory fileCollectionFactory
    ) {
        return new DefaultFilePropertyFactory(propertyHost, fileResolver, fileCollectionFactory);
    }
}
