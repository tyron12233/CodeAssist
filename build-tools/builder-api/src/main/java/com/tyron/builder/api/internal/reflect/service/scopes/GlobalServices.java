package com.tyron.builder.api.internal.reflect.service.scopes;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.PathValidation;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.changedetection.state.CrossBuildFileHashCache;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ManagedScheduledExecutor;
import com.tyron.builder.api.internal.concurrent.ManagedScheduledExecutorImpl;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.execution.steps.WorkInputListeners;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.DefaultDeleter;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileLookup;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.DeleteSpec;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.IdentityFileResolver;
import com.tyron.builder.api.internal.file.PathToFileResolver;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.impl.DefaultFileMetadata;
import com.tyron.builder.api.internal.hash.DefaultFileHasher;
import com.tyron.builder.api.internal.hash.DefaultStreamHasher;
import com.tyron.builder.api.internal.hash.FileHasher;
import com.tyron.builder.api.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.operations.BuildOperationListener;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.reflect.service.AnnotatedServiceLifecycleHandler;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.remote.inet.InetAddressFactory;
import com.tyron.builder.api.internal.service.scopes.DefaultWorkInputListeners;
import com.tyron.builder.api.internal.service.scopes.Scope;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.api.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.api.internal.snapshot.SnapshottingFilter;
import com.tyron.builder.api.internal.snapshot.impl.DirectorySnapshotterStatistics;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.model.internal.DefaultObjectFactory;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.api.tasks.util.internal.PatternSets;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.cache.internal.DefaultFileLockManager;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.ProcessMetaDataProvider;
import com.tyron.builder.cache.internal.locklistener.DefaultFileLockContentionHandler;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.cache.scopes.ScopedCache;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.AbstractVirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.DefaultFileSystemAccess;
import com.tyron.builder.internal.vfs.impl.DefaultSnapshotHierarchy;
import com.tyron.builder.internal.vfs.impl.VfsRootReference;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class GlobalServices extends DefaultServiceRegistry {

    public GlobalServices() {

    }

    public GlobalServices(ServiceRegistry parent) {
        super(parent);
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

    Deleter createDeleter() {
        return new DefaultDeleter();
    }

    StreamHasher createStreamHasher() {
        return new DefaultStreamHasher();
    }

    FileHasher createFileHasher(
            StreamHasher streamHasher
    ) {
        return new DefaultFileHasher(streamHasher);
    }

    FileOperations createFileOperations(
            FileResolver fileResolver
    ) {
        return new FileOperations() {
            @Override
            public File file(Object path) {
                return fileResolver.resolve(path);
            }

            @Override
            public File file(Object path, PathValidation validation) {
                return fileResolver.resolve(path, validation);
            }

            @Override
            public URI uri(Object path) {
                return fileResolver.resolveUri(path);
            }

            @Override
            public FileResolver getFileResolver() {
                return fileResolver;
            }

            @Override
            public String relativePath(Object path) {
                return fileResolver.resolveAsRelativePath(path);
            }

            @Override
            public ConfigurableFileCollection configurableFiles(Object... paths) {
                return null;
            }

            @Override
            public FileCollection immutableFiles(Object... paths) {
                return null;
            }

            @Override
            public ConfigurableFileTree fileTree(Object baseDir) {
                return null;
            }

            @Override
            public ConfigurableFileTree fileTree(Map<String, ?> args) {
                return null;
            }

            @Override
            public FileTree zipTree(Object zipPath) {
                return null;
            }

            @Override
            public FileTree tarTree(Object tarPath) {
                return null;
            }

            @Override
            public CopySpec copySpec() {
                return null;
            }

            @Override
            public WorkResult copy(Action<? super CopySpec> action) {
                return null;
            }

            @Override
            public WorkResult sync(Action<? super CopySpec> action) {
                return null;
            }

            @Override
            public File mkdir(Object path) {
                File file = file(path);
                if (!file.exists() && !file.mkdir()) {
                    throw new UncheckedIOException("Unable to create " + path);
                }
                return file;
            }

            @Override
            public boolean delete(Object... paths) {
                for (Object path : paths) {
                    File file = file(path);
                    if (!file.delete()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public WorkResult delete(Action<? super DeleteSpec> action) {
                return null;
            }

            @Override
            public PatternSet patternSet() {
                return null;
            }
        };
    }

    VirtualFileSystem createVirtualFileSystem() {
        VfsRootReference reference = new VfsRootReference(DefaultSnapshotHierarchy.empty(CaseSensitivity.CASE_SENSITIVE));
        return new AbstractVirtualFileSystem(reference) {
            @Override
            protected SnapshotHierarchy updateNotifyingListeners(UpdateFunction updateFunction) {
                return updateFunction.update(SnapshotHierarchy.NodeDiffListener.NOOP);
            }
        };
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


    FileLookup createFileLookup() {
        return new DefaultFileLookup();
    }

    FileLockManager createFileLockManager() {
        return new DefaultFileLockManager(new ProcessMetaDataProvider() {
            @Override
            public String getProcessIdentifier() {
                return "TEST";
            }

            @Override
            public String getProcessDisplayName() {
                return "TEST";
            }
        }, new FileLockContentionHandler() {
            @Override
            public void start(long lockId, Action<FileLockReleasedSignal> whenContended) {

            }

            @Override
            public void stop(long lockId) {

            }

            @Override
            public int reservePort() {
                return 0;
            }

            @Override
            public boolean maybePingOwner(int port,
                                          long lockId,
                                          String displayName,
                                          long timeElapsed,
                                          @Nullable FileLockReleasedSignal signal) {
                return false;
            }
        });
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

    PropertyHost createPropertyHost() {
        return PropertyHost.NO_OP;
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

    ObjectFactory createObjectFactory(
            FileCollectionFactory fileCollectionFactory,
            FilePropertyFactory filePropertyFactory
    ) {
        return new DefaultObjectFactory(fileCollectionFactory, filePropertyFactory);
    }

    DefaultListenerManager createListenerManager() {
        return new DefaultListenerManager(Scope.Global.class);
    }

    FilePropertyFactory createFilePropertyFactory(
            PropertyHost propertyHost,
            FileResolver fileResolver,
            FileCollectionFactory fileCollectionFactory
    ) {
        return new DefaultFilePropertyFactory(propertyHost, fileResolver, fileCollectionFactory);
    }
}
