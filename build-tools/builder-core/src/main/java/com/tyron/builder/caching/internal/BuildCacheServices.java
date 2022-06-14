package com.tyron.builder.caching.internal;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.caching.configuration.internal.BuildCacheServiceRegistration;
import com.tyron.builder.caching.configuration.internal.DefaultBuildCacheConfiguration;
import com.tyron.builder.caching.configuration.internal.DefaultBuildCacheServiceRegistration;
import com.tyron.builder.caching.internal.controller.BuildCacheCommandFactory;
import com.tyron.builder.caching.internal.controller.BuildCacheController;
import com.tyron.builder.caching.internal.controller.RootBuildCacheControllerRef;
import com.tyron.builder.caching.internal.controller.impl.DefaultBuildCacheCommandFactory;
import com.tyron.builder.caching.internal.origin.OriginMetadataFactory;
import com.tyron.builder.caching.internal.packaging.BuildCacheEntryPacker;
import com.tyron.builder.caching.internal.packaging.impl.DefaultTarPackerFileSystemSupport;
import com.tyron.builder.caching.internal.packaging.impl.FilePermissionAccess;
import com.tyron.builder.caching.internal.packaging.impl.GZipBuildCacheEntryPacker;
import com.tyron.builder.caching.internal.packaging.impl.TarBuildCacheEntryPacker;
import com.tyron.builder.caching.internal.packaging.impl.TarPackerFileSystemSupport;
import com.tyron.builder.caching.internal.services.BuildCacheControllerFactory;
import com.tyron.builder.caching.local.DirectoryBuildCache;
import com.tyron.builder.caching.local.internal.DirectoryBuildCacheFileStoreFactory;
import com.tyron.builder.caching.local.internal.DirectoryBuildCacheServiceFactory;
import com.tyron.builder.internal.SystemProperties;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.nativeintegration.network.HostnameLookup;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resource.local.DefaultPathKeyFileStore;
import com.tyron.builder.internal.resource.local.PathKeyFileStore;
import com.tyron.builder.internal.scopeids.id.BuildInvocationScopeId;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.util.GradleVersion;
import com.tyron.builder.util.Path;

import java.io.File;
import java.util.List;

public class BuildCacheServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.addProvider(new Object() {
            RootBuildCacheControllerRef createRootBuildCacheControllerRef() {
                return new RootBuildCacheControllerRef();
            }
        });
    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.addProvider(new Object() {

            BuildCacheConfigurationInternal createBuildCacheConfiguration(Instantiator instantiator,
                                                                          List<BuildCacheServiceRegistration> allBuildCacheServiceFactories) {
                return DirectInstantiator.INSTANCE.newInstance(DefaultBuildCacheConfiguration.class,
                        DirectInstantiator.INSTANCE, allBuildCacheServiceFactories);
            }

            DirectoryBuildCacheFileStoreFactory createDirectoryBuildCacheFileStoreFactory(
                    ChecksumService checksumService) {
                return new DirectoryBuildCacheFileStoreFactory() {
                    @Override
                    public PathKeyFileStore createFileStore(File baseDir) {
                        return new DefaultPathKeyFileStore(checksumService, baseDir);
                    }
                };
            }

            BuildCacheServiceRegistration createDirectoryBuildCacheServiceRegistration() {
                return new DefaultBuildCacheServiceRegistration(DirectoryBuildCache.class,
                        DirectoryBuildCacheServiceFactory.class);
            }

        });
    }

    @Override
    public void registerGradleServices(ServiceRegistration registration) {
        // Not build scoped because of dependency on GradleInternal for build path
        registration.addProvider(new Object() {
            private static final String GRADLE_VERSION_KEY = "gradleVersion";

            TarPackerFileSystemSupport createPackerFileSystemSupport(Deleter deleter) {
                return new DefaultTarPackerFileSystemSupport(deleter);
            }

            BuildCacheEntryPacker createResultPacker(TarPackerFileSystemSupport fileSystemSupport,
                                                     FileSystem fileSystem,
                                                     StreamHasher fileHasher,
                                                     StringInterner stringInterner) {
                return new GZipBuildCacheEntryPacker(new TarBuildCacheEntryPacker(fileSystemSupport,
                        new FilePermissionsAccessAdapter(fileSystem), fileHasher, stringInterner));
            }

            OriginMetadataFactory createOriginMetadataFactory(BuildInvocationScopeId buildInvocationScopeId,
                                                              GradleInternal gradleInternal,
                                                              HostnameLookup hostnameLookup) {
                File rootDir = gradleInternal.getRootProject().getRootDir();
                return new OriginMetadataFactory(SystemProperties.getInstance().getUserName(),
                        "ANDROID", buildInvocationScopeId.getId().asString(),
                        properties -> properties.setProperty(GRADLE_VERSION_KEY,
                                GradleVersion.current().getVersion()), hostnameLookup::getHostname);
            }

            BuildCacheCommandFactory createBuildCacheCommandFactory(BuildCacheEntryPacker packer,
                                                                    OriginMetadataFactory originMetadataFactory,
                                                                    FileSystemAccess fileSystemAccess,
                                                                    StringInterner stringInterner) {
                return new DefaultBuildCacheCommandFactory(packer, originMetadataFactory,
                        fileSystemAccess, stringInterner);
            }

            BuildCacheController createBuildCacheController(ServiceRegistry serviceRegistry,
                                                            BuildCacheConfigurationInternal buildCacheConfiguration,
                                                            BuildOperationExecutor buildOperationExecutor,
                                                            InstantiatorFactory instantiatorFactory,
                                                            GradleInternal gradle,
                                                            RootBuildCacheControllerRef rootControllerRef,
                                                            TemporaryFileProvider temporaryFileProvider) {
                if (isRoot(gradle) || isGradleBuildTaskRoot(rootControllerRef)) {
                    return doCreateBuildCacheController(serviceRegistry, buildCacheConfiguration,
                            buildOperationExecutor, instantiatorFactory, gradle,
                            temporaryFileProvider);
                } else {
                    // must be an included build or buildSrc
                    return rootControllerRef.getForNonRootBuild();
                }
            }

            private boolean isGradleBuildTaskRoot(RootBuildCacheControllerRef rootControllerRef) {
                // GradleBuild tasks operate with their own build session and tree scope.
                // Therefore, they have their own RootBuildCacheControllerRef.
                // This prevents them from reusing the build cache configuration defined by the
                // root.
                // There is no way to detect that a Gradle instance represents a GradleBuild
                // invocation.
                // If there were, that would be a better heuristic than this.
                return !rootControllerRef.isSet();
            }

            private boolean isRoot(GradleInternal gradle) {
                return gradle.isRootBuild();
            }

            private BuildCacheController doCreateBuildCacheController(ServiceRegistry serviceRegistry,
                                                                      BuildCacheConfigurationInternal buildCacheConfiguration,
                                                                      BuildOperationExecutor buildOperationExecutor,
                                                                      InstantiatorFactory instantiatorFactory,
                                                                      GradleInternal gradle,
                                                                      TemporaryFileProvider temporaryFileProvider) {
                StartParameter startParameter = gradle.getStartParameter();
                Path buildIdentityPath = gradle.getIdentityPath();
                BuildCacheControllerFactory.BuildCacheMode buildCacheMode = startParameter
                        .isBuildCacheEnabled() ?
                        BuildCacheControllerFactory.BuildCacheMode.ENABLED :
                        BuildCacheControllerFactory.BuildCacheMode.DISABLED;
                BuildCacheControllerFactory.RemoteAccessMode remoteAccessMode = startParameter
                        .isOffline() ? BuildCacheControllerFactory.RemoteAccessMode.OFFLINE :
                        BuildCacheControllerFactory.RemoteAccessMode.ONLINE;
                boolean logStackTraces =
                        startParameter.getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS;
                boolean emitDebugLogging = startParameter.isBuildCacheDebugLogging();

                return BuildCacheControllerFactory
                        .create(buildOperationExecutor, buildIdentityPath, temporaryFileProvider,
                                buildCacheConfiguration, buildCacheMode, remoteAccessMode,
                                logStackTraces, emitDebugLogging,
                                instantiatorFactory.inject(serviceRegistry));
            }
        });
    }

    private static final class FilePermissionsAccessAdapter implements FilePermissionAccess {

        private final FileSystem fileSystem;

        public FilePermissionsAccessAdapter(FileSystem fileSystem) {
            this.fileSystem = fileSystem;
        }

        @Override
        public int getUnixMode(File f) throws FileException {
            return fileSystem.getUnixMode(f);
        }

        @Override
        public void chmod(File file, int mode) throws FileException {
            fileSystem.chmod(file, mode);
        }
    }
}
