package com.tyron.builder.internal.service.scopes;

import com.google.common.hash.HashCode;
import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.changedetection.state.CrossBuildFileHashCache;
import com.tyron.builder.api.internal.changedetection.state.DefaultResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.changedetection.state.ResourceEntryFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.changedetection.state.SplitResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.initialization.loadercache.DefaultClasspathHasher;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;
import com.tyron.builder.cache.GlobalCacheLocations;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.BuildTreeScopedCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.initialization.RootBuildLifecycleListener;
import com.tyron.builder.internal.build.BuildAddedListener;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.internal.classloader.ClasspathHasher;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinterRegistry;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.impl.DefaultFileCollectionFingerprinterRegistry;
import com.tyron.builder.internal.execution.impl.DefaultOutputSnapshotter;
import com.tyron.builder.internal.file.Stat;
import com.tyron.builder.internal.fingerprint.GenericFileTreeSnapshotter;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.fingerprint.classpath.ClasspathFingerprinter;
import com.tyron.builder.internal.fingerprint.classpath.impl.DefaultClasspathFingerprinter;
import com.tyron.builder.internal.fingerprint.impl.DefaultFileCollectionSnapshotter;
import com.tyron.builder.internal.fingerprint.impl.DefaultGenericFileTreeSnapshotter;
import com.tyron.builder.internal.fingerprint.impl.DefaultInputFingerprinter;
import com.tyron.builder.internal.fingerprint.impl.FileCollectionFingerprinterRegistrations;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.os.OperatingSystem;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.serialize.HashCodeSerializer;
import com.tyron.builder.internal.snapshot.CaseSensitivity;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;
import com.tyron.builder.internal.snapshot.impl.DirectorySnapshotterStatistics;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.DefaultFileSystemAccess;
import com.tyron.builder.internal.vfs.impl.DefaultSnapshotHierarchy;
import com.tyron.builder.internal.vfs.impl.VfsRootReference;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistryFactory;
import com.tyron.builder.internal.watch.registry.impl.LinuxFileWatcherRegistryFactory;
import com.tyron.builder.internal.watch.registry.impl.WindowsFileWatcherRegistryFactory;
import com.tyron.builder.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.FileChangeListeners;
import com.tyron.builder.internal.watch.vfs.WatchableFileSystemDetector;
import com.tyron.builder.internal.watch.vfs.impl.DefaultWatchableFileSystemDetector;
import com.tyron.builder.internal.watch.vfs.impl.LocationsWrittenByCurrentBuild;
import com.tyron.builder.internal.watch.vfs.impl.WatchingNotSupportedVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.impl.WatchingVirtualFileSystem;

import net.rubygrapefruit.platform.file.FileSystems;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class VirtualFileSystemServices extends AbstractPluginServiceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualFileSystemServices.class);

    /**
     * When file system watching is enabled, this system property can be used to invalidate the entire VFS.
     *
     * @see com.tyron.builder.initialization.StartParameterBuildOptions.WatchFileSystemOption
     */
    public static final String VFS_DROP_PROPERTY = "org.gradle.vfs.drop";

    public static final String MAX_HIERARCHIES_TO_WATCH_PROPERTY = "org.gradle.vfs.watch.hierarchies.max";

    private static final int DEFAULT_MAX_HIERARCHIES_TO_WATCH = 50;
    private static final int FILE_HASHER_MEMORY_CACHE_SIZE = 400000;

    public static boolean isDropVfs(StartParameter startParameter) {
        String dropVfs = getSystemProperty(VFS_DROP_PROPERTY, startParameter.getSystemPropertiesArgs());
        return dropVfs != null && !"false".equalsIgnoreCase(dropVfs);
    }

    public static int getMaximumNumberOfWatchedHierarchies(StartParameter startParameter) {
        String fromProperty = getSystemProperty(MAX_HIERARCHIES_TO_WATCH_PROPERTY, startParameter.getSystemPropertiesArgs());
        return fromProperty != null && !fromProperty.isEmpty()
                ? Integer.parseInt(fromProperty, 10)
                : DEFAULT_MAX_HIERARCHIES_TO_WATCH;
    }

    @Nullable
    private static String getSystemProperty(String systemProperty, Map<String, String> systemPropertiesArgs) {
        return systemPropertiesArgs.getOrDefault(systemProperty, System.getProperty(systemProperty));
    }

    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new GlobalScopeServices());
    }

    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {
        registration.addProvider(new GradleUserHomeServices());
    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.addProvider(new BuildSessionServices());
    }

    private static class GlobalScopeServices {
//        FileHasherStatistics.Collector createCachingFileHasherStatisticsCollector() {
//            return new FileHasherStatistics.Collector();
//        }

        DirectorySnapshotterStatistics.Collector createDirectorySnapshotterStatisticsCollector() {
            return new DirectorySnapshotterStatistics.Collector();
        }
    }

    private static class GradleUserHomeServices {
        CrossBuildFileHashCache createCrossBuildFileHashCache(GlobalScopedCache scopedCache, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
            return new CrossBuildFileHashCache(scopedCache, inMemoryCacheDecoratorFactory, CrossBuildFileHashCache.Kind.FILE_HASHES);
        }

//        FileHasher createCachingFileHasher(
//                FileHasherStatistics.Collector statisticsCollector,
//                CrossBuildFileHashCache fileStore,
//                FileSystem fileSystem,
//                GradleUserHomeScopeFileTimeStampInspector fileTimeStampInspector,
//                StreamHasher streamHasher,
//                StringInterner stringInterner
//        ) {
//            CachingFileHasher fileHasher = new CachingFileHasher(new DefaultFileHasher(streamHasher), fileStore, stringInterner, fileTimeStampInspector, "fileHashes", fileSystem, FILE_HASHER_MEMORY_CACHE_SIZE, statisticsCollector);
//            fileTimeStampInspector.attach(fileHasher);
//            return fileHasher;
//        }

        LocationsWrittenByCurrentBuild createLocationsUpdatedByCurrentBuild(ListenerManager listenerManager) {
            LocationsWrittenByCurrentBuild locationsWrittenByCurrentBuild = new LocationsWrittenByCurrentBuild();
            listenerManager.addListener(new RootBuildLifecycleListener() {
                @Override
                public void afterStart() {
                    locationsWrittenByCurrentBuild.buildStarted();
                }

                @Override
                public void beforeComplete() {
                    locationsWrittenByCurrentBuild.buildFinished();
                }
            });
            return locationsWrittenByCurrentBuild;
        }

        WatchableFileSystemDetector createWatchableFileSystemDetector(FileSystems fileSystems) {
            return new DefaultWatchableFileSystemDetector(fileSystems);
        }

        BuildLifecycleAwareVirtualFileSystem createVirtualFileSystem(
                LocationsWrittenByCurrentBuild locationsWrittenByCurrentBuild,
                ListenerManager listenerManager,
                FileChangeListeners fileChangeListeners,
                FileSystem fileSystem,
                WatchableFileSystemDetector watchableFileSystemDetector
        ) {
            VfsRootReference
                    reference = new VfsRootReference(DefaultSnapshotHierarchy.empty(CaseSensitivity.CASE_SENSITIVE));
            BuildLifecycleAwareVirtualFileSystem virtualFileSystem = determineWatcherRegistryFactory(
                    OperatingSystem.current(),
                    path -> true)
                    .<BuildLifecycleAwareVirtualFileSystem>map(watcherRegistryFactory -> new WatchingVirtualFileSystem(
                            watcherRegistryFactory,
                            reference,
                            sectionId -> null,
                            locationsWrittenByCurrentBuild,
                            watchableFileSystemDetector,
                            fileChangeListeners
                    ))
                    .orElse(new WatchingNotSupportedVirtualFileSystem(reference));
            listenerManager.addListener((BuildAddedListener) buildState -> {
                File buildRootDir = buildState.getBuildRootDir();
                virtualFileSystem.registerWatchableHierarchy(buildRootDir);
            });

            return virtualFileSystem;
        }

        private Optional<FileWatcherRegistryFactory> determineWatcherRegistryFactory(
                OperatingSystem operatingSystem,
                Predicate<String> watchingFilter
        ) {
            if (operatingSystem.isWindows()) {
                return Optional.of(new WindowsFileWatcherRegistryFactory(watchingFilter));
            } else {
                // TODO: MacOS?
                return Optional.of(new LinuxFileWatcherRegistryFactory(watchingFilter));
            }
        }

        FileSystemAccess createFileSystemAccess(
                FileHasher hasher,
                VirtualFileSystem virtualFileSystem,
                Stat stat,
                StringInterner stringInterner,
                ListenerManager listenerManager,
                PatternSpecFactory patternSpecFactory,
                FileSystemAccess.WriteListener writeListener,
                DirectorySnapshotterStatistics.Collector statisticsCollector
        ) {
            DefaultFileSystemAccess fileSystemAccess = new DefaultFileSystemAccess(
                    hasher,
                    stringInterner,
                    stat,
                    virtualFileSystem,
                    writeListener,
                    statisticsCollector
//                    DirectoryScanner.getDefaultExcludes()
            );
//            listenerManager.addListener(new DefaultExcludesBuildListener(fileSystemAccess) {
//                @Override
//                public void settingsEvaluated(Settings settings) {
//                    super.settingsEvaluated(settings);
//                    String[] defaultExcludes = DirectoryScanner.getDefaultExcludes();
//                    patternSpecFactory.setDefaultExcludesFromSettings(defaultExcludes);
//                    PatternSpecFactory.INSTANCE.setDefaultExcludesFromSettings(defaultExcludes);
//                }
//            });
            listenerManager.addListener(new RootBuildLifecycleListener() {
                @Override
                public void afterStart() {
                    // Reset default excludes for each build
//                    DirectoryScanner.resetDefaultExcludes();
//                    String[] defaultExcludes = DirectoryScanner.getDefaultExcludes();
//                    patternSpecFactory.setDefaultExcludesFromSettings(defaultExcludes);
//                    PatternSpecFactory.INSTANCE.setDefaultExcludesFromSettings(defaultExcludes);
                }

                @Override
                public void beforeComplete() {
                }
            });
            return fileSystemAccess;
        }

        GenericFileTreeSnapshotter createGenericFileTreeSnapshotter(
                FileHasher fileHasher,
                StringInterner stringInterner
        ) {
            return new DefaultGenericFileTreeSnapshotter(fileHasher, stringInterner);
        }

        FileCollectionSnapshotter createFileCollectionSnapshotter(
                FileSystemAccess fileSystemAccess,
                GenericFileTreeSnapshotter genericFileTreeSnapshotter,
                Stat stat
        ) {
            return new DefaultFileCollectionSnapshotter(fileSystemAccess, genericFileTreeSnapshotter, stat);
        }

        ResourceSnapshotterCacheService createResourceSnapshotterCacheService(
                CrossBuildFileHashCache store
        ) {
            PersistentIndexedCache<HashCode, HashCode> resourceHashesCache = store.createCache(
                    PersistentIndexedCacheParameters
                            .of("resourceHashesCache", HashCode.class, new HashCodeSerializer()),
                    400000,
                    true);
            return new DefaultResourceSnapshotterCacheService(resourceHashesCache);
        }

        ClasspathFingerprinter createClasspathFingerprinter(ResourceSnapshotterCacheService resourceSnapshotterCacheService, FileCollectionSnapshotter fileCollectionSnapshotter, StringInterner stringInterner) {
            return new DefaultClasspathFingerprinter(resourceSnapshotterCacheService, fileCollectionSnapshotter, ResourceFilter.FILTER_NOTHING, ResourceEntryFilter.FILTER_NOTHING,
                    Collections.emptyMap(), stringInterner, LineEndingSensitivity.DEFAULT);
        }

        ClasspathHasher createClasspathHasher(ClasspathFingerprinter fingerprinter, FileCollectionFactory fileCollectionFactory) {
            return new DefaultClasspathHasher(fingerprinter, fileCollectionFactory);
        }
    }

    private static class BuildSessionServices {
        CrossBuildFileHashCache createCrossBuildFileHashCache(BuildTreeScopedCache scopedCache, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
            return new CrossBuildFileHashCache(scopedCache, inMemoryCacheDecoratorFactory, CrossBuildFileHashCache.Kind.FILE_HASHES);
        }

        FileSystemAccess createFileSystemAccess(
                FileHasher hasher,
                ListenerManager listenerManager,
                Stat stat,
                StringInterner stringInterner,
                VirtualFileSystem root,
                FileSystemAccess.WriteListener writeListener,
                DirectorySnapshotterStatistics.Collector statisticsCollector
        ) {
            DefaultFileSystemAccess buildSessionsScopedVirtualFileSystem = new DefaultFileSystemAccess(
                    hasher,
                    stringInterner,
                    stat,
                    root,
                    writeListener,
                    statisticsCollector
//                    DirectoryScanner.getDefaultExcludes()
            );

//            listenerManager.addListener(new DefaultExcludesBuildListener(buildSessionsScopedVirtualFileSystem));
            listenerManager.addListener((OutputChangeListener) affectedOutputPaths -> buildSessionsScopedVirtualFileSystem.write(affectedOutputPaths, () -> {
            }));

            return buildSessionsScopedVirtualFileSystem;
        }

        GenericFileTreeSnapshotter createGenericFileTreeSnapshotter(FileHasher hasher, StringInterner stringInterner) {
            return new DefaultGenericFileTreeSnapshotter(hasher, stringInterner);
        }

        FileCollectionSnapshotter createFileCollectionSnapshotter(FileSystemAccess fileSystemAccess, GenericFileTreeSnapshotter genericFileTreeSnapshotter, Stat stat) {
            return new DefaultFileCollectionSnapshotter(fileSystemAccess, genericFileTreeSnapshotter, stat);
        }

        FileCollectionFingerprinterRegistrations createFileCollectionFingerprinterRegistrations(
                StringInterner stringInterner,
                FileCollectionSnapshotter fileCollectionSnapshotter,
                ResourceSnapshotterCacheService resourceSnapshotterCacheService
        ) {
            return new FileCollectionFingerprinterRegistrations(
                    stringInterner,
                    fileCollectionSnapshotter,
                    resourceSnapshotterCacheService,
                    ResourceFilter.FILTER_NOTHING,
                    ResourceEntryFilter.FILTER_NOTHING,
                    new HashMap<>()
            );
        }

        FileCollectionFingerprinterRegistry createFileCollectionFingerprinterRegistry(
                FileCollectionFingerprinterRegistrations fileCollectionFingerprinterRegistrations) {
            return new DefaultFileCollectionFingerprinterRegistry(fileCollectionFingerprinterRegistrations.getRegistrants());
        }

        InputFingerprinter createInputFingerprinter(
                FileCollectionSnapshotter fileCollectionSnapshotter,
                FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry,
                ValueSnapshotter valueSnapshotter
        ) {
            return new DefaultInputFingerprinter(
                    fileCollectionSnapshotter,
                    fileCollectionFingerprinterRegistry,
                    valueSnapshotter
            );
        }

        OutputSnapshotter createOutputSnapshotter(FileCollectionSnapshotter fileCollectionSnapshotter) {
            return new DefaultOutputSnapshotter(fileCollectionSnapshotter);
        }

        ResourceSnapshotterCacheService createResourceSnapshotterCacheService(
                GlobalCacheLocations globalCacheLocations,
                CrossBuildFileHashCache store,
                ResourceSnapshotterCacheService globalCache
        ) {
            PersistentIndexedCache<HashCode, HashCode> resourceHashesCache = store.createCache(PersistentIndexedCacheParameters.of("resourceHashesCache", HashCode.class, new HashCodeSerializer()), 800000, true);
            DefaultResourceSnapshotterCacheService localCache = new DefaultResourceSnapshotterCacheService(resourceHashesCache);
            return new SplitResourceSnapshotterCacheService(globalCache, localCache, globalCacheLocations);
        }
    }
}
