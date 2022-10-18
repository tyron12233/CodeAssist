package org.gradle.internal.service.scopes;

import com.google.common.hash.HashCode;
import org.gradle.StartParameter;
import org.gradle.api.internal.changedetection.state.CrossBuildFileHashCache;
import org.gradle.api.internal.changedetection.state.DefaultResourceSnapshotterCacheService;
import org.gradle.api.internal.changedetection.state.ResourceEntryFilter;
import org.gradle.api.internal.changedetection.state.ResourceFilter;
import org.gradle.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import org.gradle.api.internal.changedetection.state.SplitResourceSnapshotterCacheService;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.initialization.loadercache.DefaultClasspathHasher;
import org.gradle.api.tasks.util.internal.PatternSpecFactory;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.cache.PersistentIndexedCacheParameters;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.scopes.BuildTreeScopedCache;
import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.initialization.RootBuildLifecycleListener;
import org.gradle.internal.build.BuildAddedListener;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.internal.classloader.ClasspathHasher;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.OutputChangeListener;
import org.gradle.internal.execution.OutputSnapshotter;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinterRegistry;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.execution.fingerprint.InputFingerprinter;
import org.gradle.internal.execution.fingerprint.impl.DefaultFileCollectionFingerprinterRegistry;
import org.gradle.internal.execution.impl.DefaultOutputSnapshotter;
import org.gradle.internal.file.Stat;
import org.gradle.internal.fingerprint.GenericFileTreeSnapshotter;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.internal.fingerprint.classpath.ClasspathFingerprinter;
import org.gradle.internal.fingerprint.classpath.impl.DefaultClasspathFingerprinter;
import org.gradle.internal.fingerprint.impl.DefaultFileCollectionSnapshotter;
import org.gradle.internal.fingerprint.impl.DefaultGenericFileTreeSnapshotter;
import org.gradle.internal.fingerprint.impl.DefaultInputFingerprinter;
import org.gradle.internal.fingerprint.impl.FileCollectionFingerprinterRegistrations;
import org.gradle.internal.hash.FileHasher;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.serialize.HashCodeSerializer;
import org.gradle.internal.snapshot.CaseSensitivity;
import org.gradle.internal.snapshot.ValueSnapshotter;
import org.gradle.internal.snapshot.impl.DirectorySnapshotterStatistics;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.internal.vfs.VirtualFileSystem;
import org.gradle.internal.vfs.impl.DefaultFileSystemAccess;
import org.gradle.internal.vfs.impl.DefaultSnapshotHierarchy;
import org.gradle.internal.vfs.impl.VfsRootReference;
import org.gradle.internal.watch.registry.FileWatcherRegistryFactory;
import org.gradle.internal.watch.registry.impl.LinuxFileWatcherRegistryFactory;
import org.gradle.internal.watch.registry.impl.WindowsFileWatcherRegistryFactory;
import org.gradle.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import org.gradle.internal.watch.vfs.FileChangeListeners;
import org.gradle.internal.watch.vfs.WatchableFileSystemDetector;
import org.gradle.internal.watch.vfs.impl.DefaultWatchableFileSystemDetector;
import org.gradle.internal.watch.vfs.impl.LocationsWrittenByCurrentBuild;
import org.gradle.internal.watch.vfs.impl.WatchingNotSupportedVirtualFileSystem;
import org.gradle.internal.watch.vfs.impl.WatchingVirtualFileSystem;

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
     * @see org.gradle.initialization.StartParameterBuildOptions.WatchFileSystemOption
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
