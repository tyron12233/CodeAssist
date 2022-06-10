package org.gradle.internal.session;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.gradle.StartParameter;
import org.gradle.api.Action;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.attributes.DefaultImmutableAttributesFactory;
import org.gradle.api.internal.changedetection.state.CrossBuildFileHashCache;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.api.internal.project.BuildOperationCrossProjectConfigurator;
import org.gradle.api.internal.project.CrossProjectConfigurator;
import org.gradle.api.invocation.Gradle;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.internal.BuildScopeCacheDir;
import org.gradle.cache.internal.CleanupActionFactory;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.scopes.DefaultBuildTreeScopedCache;
import org.gradle.cache.scopes.BuildTreeScopedCache;
import org.gradle.groovy.scripts.internal.DefaultScriptSourceHasher;
import org.gradle.groovy.scripts.internal.ScriptSourceHasher;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.BuildClientMetaData;
import org.gradle.initialization.BuildEventConsumer;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.initialization.layout.BuildLayout;
import org.gradle.initialization.layout.BuildLayoutConfiguration;
import org.gradle.initialization.layout.BuildLayoutFactory;
import org.gradle.initialization.layout.ProjectCacheDir;
import org.gradle.internal.build.BuildLayoutValidator;
import org.gradle.internal.buildevents.BuildStartedTime;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.hash.ChecksumService;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.model.CalculatedValueContainerFactory;
import org.gradle.internal.model.StateTransitionControllerFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.scopeids.id.UserScopeId;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.PluginServiceRegistry;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.WorkerSharedBuildSessionScopeServices;
import org.gradle.internal.time.Clock;
import org.gradle.internal.work.DefaultAsyncWorkTracker;
import org.gradle.plugin.use.internal.InjectedPluginClasspath;
import org.gradle.vcs.VcsMapping;
import org.gradle.vcs.VersionControlSpec;
import org.gradle.vcs.internal.VcsMappingsStore;
import org.gradle.vcs.internal.VcsResolver;

import org.apache.commons.io.FileUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

// accessed reflectively
@SuppressWarnings("unused")
public class BuildSessionScopeServices extends WorkerSharedBuildSessionScopeServices {
    private final StartParameterInternal startParameter;
    private final BuildRequestMetaData buildRequestMetaData;
    private final ClassPath injectedPluginClassPath;
    private final BuildCancellationToken buildCancellationToken;
    private final BuildClientMetaData buildClientMetaData;
    private final BuildEventConsumer buildEventConsumer;

    public BuildSessionScopeServices(StartParameterInternal startParameter, BuildRequestMetaData buildRequestMetaData, ClassPath injectedPluginClassPath, BuildCancellationToken buildCancellationToken, BuildClientMetaData buildClientMetaData, BuildEventConsumer buildEventConsumer) {
        this.startParameter = startParameter;
        this.buildRequestMetaData = buildRequestMetaData;
        this.injectedPluginClassPath = injectedPluginClassPath;
        this.buildCancellationToken = buildCancellationToken;
        this.buildClientMetaData = buildClientMetaData;
        this.buildEventConsumer = buildEventConsumer;
    }

    void configure(ServiceRegistration registration, List<PluginServiceRegistry> pluginServiceRegistries) {
        registration.add(StartParameterInternal.class, startParameter);
        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceRegistries) {
            pluginServiceRegistry.registerBuildSessionServices(registration);
        }
        registration.add(InjectedPluginClasspath.class, new InjectedPluginClasspath(injectedPluginClassPath));
        registration.add(BuildCancellationToken.class, buildCancellationToken);
        registration.add(BuildRequestMetaData.class, buildRequestMetaData);
        registration.add(BuildClientMetaData.class, buildClientMetaData);
        registration.add(BuildEventConsumer.class, buildEventConsumer);
        registration.add(CalculatedValueContainerFactory.class);
        registration.add(StateTransitionControllerFactory.class);
        registration.add(BuildLayoutValidator.class);
        registration.add(DefaultAsyncWorkTracker.class);
        // Must be no higher than this scope as needs cache repository services.
//        registration.addProvider(new ScopeIdsServices());

        // from ToolingBuildScopeServices
    }

//    PendingChangesManager createPendingChangesManager(ListenerManager listenerManager) {
//        return new PendingChangesManager(listenerManager);
//    }
//
//    DefaultDeploymentRegistry createDeploymentRegistry(PendingChangesManager pendingChangesManager, BuildOperationExecutor buildOperationExecutor, ObjectFactory objectFactory) {
//        return new DefaultDeploymentRegistry(pendingChangesManager, buildOperationExecutor, objectFactory);
//    }

    DefaultListenerManager createListenerManager(DefaultListenerManager parent) {
        return parent.createChild(Scopes.BuildSession.class);
    }

    CrossProjectConfigurator createCrossProjectConfigurator(BuildOperationExecutor buildOperationExecutor) {
        return new BuildOperationCrossProjectConfigurator(buildOperationExecutor);
    }

    BuildLayout createBuildLayout(BuildLayoutFactory buildLayoutFactory, StartParameter startParameter) {
        return buildLayoutFactory.getLayoutFor(new BuildLayoutConfiguration(startParameter));
    }

    FileResolver createFileResolver(FileLookup fileLookup, BuildLayout buildLayout) {
        return fileLookup.getFileResolver(buildLayout.getRootDirectory());
    }

    ProjectCacheDir createProjectCacheDir(
            GradleUserHomeDirProvider userHomeDirProvider,
            BuildLayout buildLayout,
            Deleter deleter,
            ProgressLoggerFactory progressLoggerFactory,
            StartParameter startParameter
    ) {
        BuildScopeCacheDir
                cacheDir = new BuildScopeCacheDir(userHomeDirProvider, buildLayout, startParameter);
        return new ProjectCacheDir(cacheDir.getDir(), progressLoggerFactory, deleter);
    }

    BuildTreeScopedCache createBuildTreeScopedCache(ProjectCacheDir projectCacheDir, CacheRepository cacheRepository) {
        return new DefaultBuildTreeScopedCache(projectCacheDir.getDir(), cacheRepository);
    }
//
//    BuildSessionScopeFileTimeStampInspector createFileTimeStampInspector(BuildTreeScopedCache scopedCache) {
//        File workDir = scopedCache.baseDirForCache("fileChanges");
//        return new BuildSessionScopeFileTimeStampInspector(workDir);
//    }
//
    ScriptSourceHasher createScriptSourceHasher() {
        return new DefaultScriptSourceHasher();
    }

    DefaultImmutableAttributesFactory createImmutableAttributesFactory(IsolatableFactory isolatableFactory, NamedObjectInstantiator instantiator) {
        return new DefaultImmutableAttributesFactory(isolatableFactory, instantiator);
    }

//    UserScopeId createUserScopeId(PersistentScopeIdLoader persistentScopeIdLoader) {
//        return persistentScopeIdLoader.getUser();
//    }
//
//    protected WorkspaceScopeId createWorkspaceScopeId(PersistentScopeIdLoader persistentScopeIdLoader) {
//        return persistentScopeIdLoader.getWorkspace();
//    }

    BuildStartedTime createBuildStartedTime(Clock clock, BuildRequestMetaData buildRequestMetaData) {
        long currentTime = clock.getCurrentTime();
        return BuildStartedTime.startingAt(Math.min(currentTime, buildRequestMetaData.getStartTime()));
    }

    FeaturePreviews createExperimentalFeatures() {
        return new FeaturePreviews();
    }
//
    CleanupActionFactory createCleanupActionFactory(BuildOperationExecutor buildOperationExecutor) {
        return new CleanupActionFactory(buildOperationExecutor);
    }
//
//    protected ExecFactory decorateExecFactory(ExecFactory execFactory, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, BuildCancellationToken buildCancellationToken, ObjectFactory objectFactory, JavaModuleDetector javaModuleDetector) {
//        return execFactory.forContext()
//                .withFileResolver(fileResolver)
//                .withFileCollectionFactory(fileCollectionFactory)
//                .withInstantiator(instantiator)
//                .withBuildCancellationToken(buildCancellationToken)
//                .withObjectFactory(objectFactory)
//                .withJavaModuleDetector(javaModuleDetector)
//                .build();
//    }

    CrossBuildFileHashCacheWrapper createCrossBuildChecksumCache(BuildTreeScopedCache scopedCache, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
        CrossBuildFileHashCache crossBuildCache = new CrossBuildFileHashCache(scopedCache, inMemoryCacheDecoratorFactory, CrossBuildFileHashCache.Kind.CHECKSUMS);
        return new CrossBuildFileHashCacheWrapper(crossBuildCache);
    }

//    ChecksumService createChecksumService(
//            StringInterner stringInterner,
//            FileSystem fileSystem,
//            CrossBuildFileHashCacheWrapper crossBuildCache,
//            BuildSessionScopeFileTimeStampInspector inspector,
//            FileHasherStatistics.Collector statisticsCollector
//    ) {
//        return new DefaultChecksumService(stringInterner, crossBuildCache.delegate, fileSystem, inspector, statisticsCollector);
//    }

    protected ChecksumService createChecksumService() {
        return new ChecksumService() {
            @Override
            public HashCode md5(File file) {
                try {
                    return Hashing.md5().hashBytes(FileUtils.readFileToByteArray(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public HashCode sha1(File file) {
                try {
                    return Hashing.sha1().hashBytes(FileUtils.readFileToByteArray(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public HashCode sha256(File file) {
                try {
                    return Hashing.sha256().hashBytes(FileUtils.readFileToByteArray(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public HashCode sha512(File file) {
                try {
                    return Hashing.sha512().hashBytes(FileUtils.readFileToByteArray(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public HashCode hash(File src, String algorithm) {
                switch (algorithm) {
                    case "md5": return md5(src);
                    case "sha1": return sha1(src);
                    case "sha256": return sha256(src);
                    default: return sha512(src);
                }
            }
        };
    }

//    UserInputHandler createUserInputHandler(BuildRequestMetaData requestMetaData, OutputEventListenerManager outputEventListenerManager, Clock clock) {
//        if (!requestMetaData.isInteractive()) {
//            return new NonInteractiveUserInputHandler();
//        }
//
//        return new DefaultUserInputHandler(outputEventListenerManager.getBroadcaster(), clock, new DefaultUserInputReader());
//    }

    // Wraps CrossBuildFileHashCache so that it doesn't conflict
    // with other services in different scopes
    static class CrossBuildFileHashCacheWrapper implements Closeable {
        private final CrossBuildFileHashCache delegate;

        private CrossBuildFileHashCacheWrapper(CrossBuildFileHashCache delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    VcsMappingsStore createVcsMappingStore() {
        return new VcsMappingStoreImpl();
    }

    private static class VcsMappingStoreImpl implements VcsMappingsStore, VcsResolver {

        @Override
        public VcsResolver asResolver() {
            return this;
        }

        @Override
        public void addRule(Action<? super VcsMapping> rule, Gradle gradle) {

        }

        @Nullable
        @Override
        public VersionControlSpec locateVcsFor(ModuleComponentSelector selector) {
            return null;
        }

        @Override
        public boolean hasRules() {
            return false;
        }
    }
}
