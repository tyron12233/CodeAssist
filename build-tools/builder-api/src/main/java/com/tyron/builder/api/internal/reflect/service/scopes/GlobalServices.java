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
import com.tyron.builder.api.internal.concurrent.ManagedScheduledExecutor;
import com.tyron.builder.api.internal.concurrent.ManagedScheduledExecutorImpl;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.execution.steps.WorkInputListeners;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.DefaultDeleter;
import com.tyron.builder.api.internal.file.DeleteSpec;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.IdentityFileResolver;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.file.impl.DefaultFileMetadata;
import com.tyron.builder.api.internal.hash.DefaultFileHasher;
import com.tyron.builder.api.internal.hash.DefaultStreamHasher;
import com.tyron.builder.api.internal.hash.FileHasher;
import com.tyron.builder.api.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.operations.BuildOperationListener;
import com.tyron.builder.api.internal.reflect.service.AnnotatedServiceLifecycleHandler;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.service.scopes.DefaultWorkInputListeners;
import com.tyron.builder.api.internal.service.scopes.Scope;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.api.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.api.internal.snapshot.SnapshottingFilter;
import com.tyron.builder.api.internal.snapshot.impl.DirectorySnapshotterStatistics;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.AbstractVirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.DefaultFileSystemAccess;
import com.tyron.builder.internal.vfs.impl.DefaultSnapshotHierarchy;
import com.tyron.builder.internal.vfs.impl.VfsRootReference;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class GlobalServices extends DefaultServiceRegistry {

    public GlobalServices() {
        register(registration -> {
            registration.add(DefaultListenerManager.class, new DefaultListenerManager(Scope.Global.class));
            registration.add(DocumentationRegistry.class);
        });
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

    FileResolver createFileResolver() {
        return new IdentityFileResolver();
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
                if (!file.mkdir()) {
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
}
