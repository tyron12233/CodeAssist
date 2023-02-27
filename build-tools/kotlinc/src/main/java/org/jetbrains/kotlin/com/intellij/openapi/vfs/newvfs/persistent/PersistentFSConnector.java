package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentStringEnumerator;
import org.jetbrains.kotlin.com.intellij.util.io.ResizeableMappedFile;
import org.jetbrains.kotlin.com.intellij.util.io.ScannableDataEnumeratorEx;
import org.jetbrains.kotlin.com.intellij.util.io.SimpleStringPersistentEnumerator;
import org.jetbrains.kotlin.com.intellij.util.io.StorageLockContext;
import org.jetbrains.kotlin.com.intellij.util.io.storage.AbstractRecordsTable;
import org.jetbrains.kotlin.com.intellij.util.io.storage.Storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Static helper responsible for 'connecting' (opening, initializing)
 * {@linkplain PersistentFSConnection} object,
 * and closing it. It does a few tries to initialize VFS storages, tries to correct/rebuild
 * broken parts, and so on.
 */
final class PersistentFSConnector {

    private static final Lock ourOpenCloseLock = new ReentrantLock();

    private static final Logger LOG = Logger.getInstance(PersistentFSConnector.class);

    private static final int MAX_INITIALIZATION_ATTEMPTS = 10;
    private static final AtomicInteger INITIALIZATION_COUNTER = new AtomicInteger();
    private static final StorageLockContext PERSISTENT_FS_STORAGE_CONTEXT =
            new StorageLockContext(false, true);

    public static @NotNull PersistentFSConnection connect(@NotNull String cachesDir,
                                                          int version,
                                                          boolean useContentHashes) {
        ourOpenCloseLock.lock();
        try {
            return init(cachesDir, version, useContentHashes);
        } finally {
            ourOpenCloseLock.unlock();
        }
    }

    private static @NotNull PersistentFSConnection init(@NotNull String cachesDir,
                                                        int expectedVersion,
                                                        boolean useContentHashes) {
        Exception exception = null;
        for (int i = 0; i < MAX_INITIALIZATION_ATTEMPTS; i++) {
            INITIALIZATION_COUNTER.incrementAndGet();
            Pair<PersistentFSConnection, Exception> pair =
                    tryInit(cachesDir, expectedVersion, useContentHashes);
            exception = pair.getSecond();
            if (exception == null) {
                return pair.getFirst();
            }
        }
        throw new RuntimeException("Can't initialize filesystem storage", exception);
    }

    private static @NotNull Pair<PersistentFSConnection, Exception> tryInit(@NotNull String cachesDir,
                                                                            int expectedVersion,
                                                                            boolean useContentHashes) {
        AbstractAttributesStorage attributes = null;
        PersistentFSRecordsStorage records = null;
        PersistentFSPaths persistentFSPaths = new PersistentFSPaths(cachesDir);
        Path basePath = new File(cachesDir).getAbsoluteFile().toPath();
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            return Pair.create(null, e);
        }
        final Path namesFile = basePath.resolve("names" + PersistentFSPaths.VFS_FILES_EXTENSION);
        final Path attributesFile =
                basePath.resolve("attrib" + PersistentFSPaths.VFS_FILES_EXTENSION);
        final Path contentsFile =
                basePath.resolve("content" + PersistentFSPaths.VFS_FILES_EXTENSION);
        final Path contentsHashesFile =
                basePath.resolve("contentHashes" + PersistentFSPaths.VFS_FILES_EXTENSION);
        final Path recordsFile =
                basePath.resolve("records" + PersistentFSPaths.VFS_FILES_EXTENSION);
        final Path enumeratedAttributesFile =
                basePath.resolve("enum_attrib" + PersistentFSPaths.VFS_FILES_EXTENSION);
        final File vfsDependentEnumBaseFile = persistentFSPaths.getVfsEnumBaseFile();
        try {
            if (persistentFSPaths.getCorruptionMarkerFile().exists()) {
                throw new IOException("Corruption marker file found");
            }

//            names = createFileNamesEnumerator(namesFile);

            records = PersistentFSRecordsStorageFactory.createStorage(recordsFile);

            attributes = createAttributesStorage(attributesFile);
            SimpleStringPersistentEnumerator enumeratedAttributes =
                    new SimpleStringPersistentEnumerator(enumeratedAttributesFile);

            return Pair.create(new PersistentFSConnection(records,
                    attributes,
                    persistentFSPaths,
                    enumeratedAttributes), null);
        } catch (Exception e) { // IOException, IllegalArgumentException
            return Pair.create(null, e);
        }
    }


    private static AbstractAttributesStorage createAttributesStorage(final Path attributesFile) throws IOException {
        LOG.info("VFS uses regular attributes storage");
        return new AttributesStorageOld(FSRecords.bulkAttrReadSupport,
                FSRecords.inlineAttributes,
                new Storage(attributesFile, PersistentFSConnection.REASONABLY_SMALL) {
                    @Override
                    protected AbstractRecordsTable createRecordsTable(@NotNull StorageLockContext context,
                                                                      @NotNull Path recordsFile) throws IOException {
//                        return FSRecords.inlineAttributes && FSRecords.useSmallAttrTable
//                                ? new CompactRecordsTable(recordsFile, context, false)
//                                : super.createRecordsTable(context, recordsFile);
                        return super.createRecordsTable(context, recordsFile);
                    }
                });
    }

    @NotNull
    private static ScannableDataEnumeratorEx<String> createFileNamesEnumerator(final Path namesFile) throws IOException {
//        if (FSRecords.USE_FAST_NAMES_IMPLEMENTATION) {
//            LOG.info("VFS uses non-strict names enumerator");
//            final ResizeableMappedFile mappedFile = new ResizeableMappedFile(
//                    namesFile,
//                    10 * IOUtil.MiB,
//                    PERSISTENT_FS_STORAGE_CONTEXT,
//                    IOUtil.MiB,
//                    false
//            );
//            return new OffsetBasedNonStrictStringsEnumerator(mappedFile);
//        }
//        else {
        LOG.info("VFS uses strict names enumerator");
        return new PersistentStringEnumerator(namesFile, PERSISTENT_FS_STORAGE_CONTEXT);
//        }
    }

    public static void disconnect(@NotNull final PersistentFSConnection connection) {
        ourOpenCloseLock.lock();
        try {
//            InvertedNameIndex.clear();
            connection.doForce();
            connection.closeFiles();
        }
        catch (IOException e) {
            connection.handleError(e);
        }
        finally {
            ourOpenCloseLock.unlock();
        }
    }
}