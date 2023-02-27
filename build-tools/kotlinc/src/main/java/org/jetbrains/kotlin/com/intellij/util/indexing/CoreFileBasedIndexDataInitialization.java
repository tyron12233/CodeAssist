package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.psi.search.FilenameIndex;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.IntSetUtils;
import org.jetbrains.kotlin.com.intellij.util.indexing.CorruptionMarker;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexImpl;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexInfrastructureExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexConfiguration;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexDataInitializer;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexInfrastructure;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexVersionRegistrationSink;
import org.jetbrains.kotlin.com.intellij.util.indexing.RebuildStatus;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.FileBasedIndexLayoutSettings;
import org.jetbrains.kotlin.com.intellij.util.io.DataOutputStream;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
public class CoreFileBasedIndexDataInitialization extends IndexDataInitializer<IndexConfiguration> {

    private static final @NotNull Logger LOG = Logger.getInstance(
            CoreFileBasedIndexDataInitialization.class);
    @NotNull
    private final IntSet myStaleIds = IntSetUtils.synchronize(new IntOpenHashSet());
    @NotNull
    private final IntSet myDirtyFileIds = IntSetUtils.synchronize(new IntOpenHashSet());
    @NotNull
    private final IndexVersionRegistrationSink myRegistrationResultSink =
            new IndexVersionRegistrationSink();
    @NotNull
    private final IndexConfiguration myState = new IndexConfiguration();
    private final CoreFileBasedIndex fileBasedIndex;
    private final RegisteredIndexes registeredIndexes;
    private boolean myCurrentVersionCorrupted;

    public CoreFileBasedIndexDataInitialization(@NotNull CoreFileBasedIndex fileBasedIndex,
                                                RegisteredIndexes registeredIndexes) {
        this.fileBasedIndex = fileBasedIndex;
        this.registeredIndexes = registeredIndexes;
    }

    @NonNull
    @Override
    protected String getInitializationFinishedMessage(IndexConfiguration initializationResult) {
        return "Initialized indexes: " + initializationResult.getIndexIDs() + ".";
    }

    @Override
    protected IndexConfiguration finish() {
        try {
            myState.finalizeFileTypeMappingForIndices();

            //

            myState.freeze();
            registeredIndexes.setState(myState);

            for (ID<?, ?> indexId : myState.getIndexIDs()) {
                try {
                    RebuildStatus.clearIndexIfNecessary(indexId, () -> fileBasedIndex.clearIndex(indexId));
                } catch (StorageException e) {
                    fileBasedIndex.requestRebuild(indexId);
                    FileBasedIndexImpl.LOG.error(e);
                }
            }
        } finally {
            registeredIndexes.ensureLoadedIndexesUpToDate();
            registeredIndexes.markInitialized();
            saveRegisteredIndicesAndDropUnregisteredOnes(myState.getIndexIDs());
        }

        return myState;
    }

    private @NotNull Collection<ThrowableRunnable<?>> initAssociatedDataForExtensions() {
        ExtensionPointImpl<FileBasedIndexExtension<?, ?>> extPoint =
                (ExtensionPointImpl<FileBasedIndexExtension<?, ?>>)FileBasedIndexExtension.EXTENSION_POINT_NAME.getPoint(null);
        Iterator<FileBasedIndexExtension<?, ?>> extensions = extPoint.iterator();
        List<ThrowableRunnable<?>> tasks = new ArrayList<>(extPoint.size());
        while (extensions.hasNext()) {
            FileBasedIndexExtension<?, ?> extension = extensions.next();
            if (extension == null) {
                break;
            }
            RebuildStatus.registerIndex(extension.getName());
            registeredIndexes.registerIndexExtension(extension);
            tasks.add(() -> {
                if (IOUtil.isSharedCachesEnabled()) {
                    IOUtil.OVERRIDE_BYTE_BUFFERS_USE_NATIVE_BYTE_ORDER_PROP.set(false);
                }

                try {
                    CoreFileBasedIndex.registerIndexer(extension,
                            myState,
                            myRegistrationResultSink,
                            myStaleIds,
                            myDirtyFileIds);
                } catch (IOException io) {
                    throw io;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                } finally {
                    IOUtil.OVERRIDE_BYTE_BUFFERS_USE_NATIVE_BYTE_ORDER_PROP.remove();
                }
            });
        }
        return tasks;
    }

    @SuppressWarnings("KotlinInternalInJava")
    @NonNull
    @Override
    protected Collection<ThrowableRunnable<?>> prepareTasks() {
        Collection<ThrowableRunnable<?>> tasks = initAssociatedDataForExtensions();

        myCurrentVersionCorrupted = CorruptionMarker.requireInvalidation();
        boolean storageLayoutChanged = FileBasedIndexLayoutSettings.INSTANCE.loadUsedLayout();
        for (FileBasedIndexInfrastructureExtension ex : FileBasedIndexInfrastructureExtension.EP_NAME.getExtensions()) {
            FileBasedIndexInfrastructureExtension.InitializationResult result = ex.initialize(
                    Objects.requireNonNull(FileBasedIndexLayoutSettings.INSTANCE.getUsedLayout()).id);
            myCurrentVersionCorrupted = myCurrentVersionCorrupted ||
                                        result == FileBasedIndexInfrastructureExtension.InitializationResult.INDEX_REBUILD_REQUIRED;
        }
        myCurrentVersionCorrupted = myCurrentVersionCorrupted || storageLayoutChanged;

        if (myCurrentVersionCorrupted) {
            CorruptionMarker.dropIndexes();
        }
        return tasks;
    }

    @SuppressWarnings("removal")
    private static void saveRegisteredIndicesAndDropUnregisteredOnes(@NotNull Collection<? extends ID<?, ?>> ids) {
        if (ApplicationManager.getApplication().isDisposed() || !IndexInfrastructure.hasIndices()) {
            return;
        }

        final Path registeredIndicesFile = PathManager.getIndexRoot().toPath().resolve("registered");
        final Set<String> indicesToDrop = new HashSet<>();

        boolean exceptionThrown = false;
        if (Files.exists(registeredIndicesFile)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(registeredIndicesFile)))) {
                int size = in.readInt();
                for (int idx = 0; idx < size; idx++) {
                    indicesToDrop.add(IOUtil.readString(in));
                }
            }
            catch (Throwable e) {
                // workaround for IDEA-194253
                LOG.info(e);
                exceptionThrown = true;
                ids.stream().map(ID::getName).forEach(indicesToDrop::add);
            }
        }

        boolean dropFilenameIndex = FileBasedIndexExtension.USE_VFS_FOR_FILENAME_INDEX &&
                                    indicesToDrop.contains(FilenameIndex.NAME.getName());
        if (!exceptionThrown) {
            for (ID<?, ?> key : ids) {
                if (dropFilenameIndex && key == FilenameIndex.NAME) continue;
                indicesToDrop.remove(key.getName());
            }
        }

        if (!indicesToDrop.isEmpty()) {
            Collection<String> filtered = !dropFilenameIndex ? indicesToDrop :
                    ContainerUtil.filter(indicesToDrop, o -> !FilenameIndex.NAME.getName().equals(o));
            if (!filtered.isEmpty()) LOG.info("Dropping indices:" + String.join(",", filtered));
            for (String s : indicesToDrop) {
                try {
                    FileUtil.deleteWithRenaming(IndexInfrastructure.getFileBasedIndexRootDir(s).toFile());
                }
                catch (IOException e) {
                    LOG.warn(e);
                }
            }
        }

        try {
            Files.createDirectories(registeredIndicesFile.getParent());
            try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(registeredIndicesFile)))) {
                os.writeInt(ids.size());
                for (ID<?, ?> id : ids) {
                    IOUtil.writeString(id.getName(), os);
                }
            }
        }
        catch (IOException e) {
            LOG.warn(e);
        }
    }

}
