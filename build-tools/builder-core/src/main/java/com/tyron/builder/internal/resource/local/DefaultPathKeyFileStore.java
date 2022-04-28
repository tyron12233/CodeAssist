package com.tyron.builder.internal.resource.local;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.EmptyFileVisitor;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
import com.tyron.builder.api.internal.file.collections.SingleIncludePatternFileTree;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.util.internal.GFileUtils;

import org.apache.commons.io.FileUtils;

import kotlin.io.FilesKt;

/**
 * File store that accepts the target path as the key for the entry.
 *
 * This implementation is explicitly NOT THREAD SAFE. Concurrent access must be organised externally.
 * <p>
 * There is always at most one entry for a given key for this file store. If an entry already exists at the given path, it will be overwritten.
 * Paths can contain directory components, which will be created on demand.
 * <p>
 * This file store is self repairing in so far that any files partially written before a fatal error will be ignored and
 * removed at a later time.
 * <p>
 * This file store also provides searching via relative ant path patterns.
 */
public class DefaultPathKeyFileStore implements PathKeyFileStore {

    private final ChecksumService checksumService;

    /*
        When writing a file into the filestore a marker file with this suffix is written alongside,
        then removed after the write. This is used to detect partially written files (due to a serious crash)
        and to silently clean them.
     */
    public static final String IN_PROGRESS_MARKER_FILE_SUFFIX = ".fslck";

    private File baseDir;

    public DefaultPathKeyFileStore(ChecksumService checksumService, File baseDir) {
        this.checksumService = checksumService;
        this.baseDir = baseDir;
    }

    protected File getBaseDir() {
        return baseDir;
    }

    private File getFile(String... path) {
        File result = baseDir;
        for (String p : path) {
            result = new File(result, p);
        }
        return result;
    }

    private File getFileWhileCleaningInProgress(String... path) {
        File file = getFile(path);
        File markerFile = getInProgressMarkerFile(file);
        if (markerFile.exists()) {
            deleteFileQuietly(file);
            deleteFileQuietly(markerFile);
        }
        return file;
    }

    @Override
    public LocallyAvailableResource add(final String path, final Action<File> addAction) {
        try {
            return doAdd(path, file -> {
                try {
                    addAction.execute(file);
                } catch (Throwable e) {
                    throw new FileStoreAddActionException(String.format("Failed to add into filestore '%s' at '%s' ", getBaseDir().getAbsolutePath(), path), e);
                }
            });
        } catch (FileStoreAddActionException e) {
            throw e;
        } catch (Throwable e) {
            throw new FileStoreException(String.format("Failed to add into filestore '%s' at '%s' ", getBaseDir().getAbsolutePath(), path), e);
        }
    }

    @Override
    public LocallyAvailableResource move(String path, final File source) {
        if (!source.exists()) {
            throw new FileStoreException(String.format("Cannot move '%s' into filestore @ '%s' as it does not exist", source, path));
        }

        try {
            return doAdd(path, new Action<File>() {
                @Override
                public void execute(File file) {
                    if (source.isDirectory()) {
                        GFileUtils.moveExistingDirectory(source, file);
                    } else {
                        GFileUtils.moveExistingFile(source, file);
                    }
                }
            });
        } catch (Throwable e) {
            throw new FileStoreException(String.format("Failed to move file '%s' into filestore at '%s' ", source, path), e);
        }
    }

    private LocallyAvailableResource doAdd(String path, Action<File> action) {
        File destination = getFile(path);
        doAdd(destination, action);
        return entryAt(path);
    }

    protected void doAdd(File destination, Action<File> action) {
        GFileUtils.parentMkdirs(destination);
        File inProgressMarkerFile = getInProgressMarkerFile(destination);
        GFileUtils.touch(inProgressMarkerFile);
        try {
            FileUtils.deleteQuietly(destination);
            action.execute(destination);
        } catch (Throwable t) {
            FileUtils.deleteQuietly(destination);
            throw UncheckedException.throwAsUncheckedException(t);
        } finally {
            deleteFileQuietly(inProgressMarkerFile);
        }
    }

    @Override
    public Set<? extends LocallyAvailableResource> search(String pattern) {
        if (!getBaseDir().exists()) {
            return Collections.emptySet();
        }

        final Set<LocallyAvailableResource> entries = new HashSet<LocallyAvailableResource>();
        findFiles(pattern).visit(new EmptyFileVisitor() {
            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                final File file = fileDetails.getFile();
                // We cannot clean in progress markers, or in progress files here because
                // the file system visitor stuff can't handle the file system mutating while visiting
                if (!isInProgressMarkerFile(file) && !isInProgressFile(file)) {
                    entries.add(entryAt(file));
                }
            }
        });

        return entries;
    }

    private File getInProgressMarkerFile(File file) {
        return new File(file.getParent(), file.getName() + IN_PROGRESS_MARKER_FILE_SUFFIX);
    }

    private boolean isInProgressMarkerFile(File file) {
        return FilesKt.getExtension(file).equals(IN_PROGRESS_MARKER_FILE_SUFFIX);
    }

    private boolean isInProgressFile(File file) {
        return getInProgressMarkerFile(file).exists();
    }

    private MinimalFileTree findFiles(String pattern) {
        return new SingleIncludePatternFileTree(baseDir, pattern);
    }

    protected LocallyAvailableResource entryAt(File file) {
        return entryAt(relativePath(baseDir, file));
    }

    protected LocallyAvailableResource entryAt(final String path) {
        return new DefaultLocallyAvailableResource(getFile(path), checksumService);
    }

    @Override
    public LocallyAvailableResource get(String... path) {
        final File file = getFileWhileCleaningInProgress(path);
        if (file.exists()) {
            return new DefaultLocallyAvailableResource(getFile(path), checksumService);
        } else {
            return null;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void deleteFileQuietly(File file) {
        file.delete();
    }

    /**
     * Returns a relative path from 'from' to 'to'
     *
     * @param from where to calculate from
     * @param to where to calculate to
     * @return The relative path
     */
    public static String relativePath(File from, File to) {
        try {
            return from.toPath().relativize(to.toPath()).toString().replace(File.separatorChar, '/');
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}