package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.diagnostic.PluginException;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ProjectFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VFileProperty;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

import java.util.Collection;

import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;

import java.util.*;

/**
 * @author dmitrylomov
 * @see FileBasedIndexExtension
 */
public abstract class FileBasedIndex {
    public abstract void iterateIndexableFiles(@NonNull ContentIterator processor,
                                               @NonNull Project project,
                                               @Nullable ProgressIndicator indicator);

    /**
     * @return the file which the current thread is indexing right now, or {@code null} if
     * current thread isn't indexing.
     */
    @Nullable
    public abstract VirtualFile getFileBeingCurrentlyIndexed();

    /**
     * @return the file which the current thread is writing evaluated values of indexes right now,
     * or {@code null} if current thread isn't writing index values.
     */
    @Nullable
    public abstract IndexWritingFile getFileWritingCurrentlyIndexes();

    public static class IndexWritingFile {
        public final int fileId;

        public IndexWritingFile(int id) {
            fileId = id;
        }
    }

    //    @ApiStatus.Internal
    public void registerProjectFileSets(@NonNull Project project) {
        throw new UnsupportedOperationException();
    }

    //    @ApiStatus.Internal
    public void removeProjectFileSets(@NonNull Project project) {
        throw new UnsupportedOperationException();
    }

    /**
     * Should be called only in dumb mode and only in a read action
     */
//    @ApiStatus.Internal
    @Nullable
    public DumbModeAccessType getCurrentDumbModeAccessType() {
        throw new UnsupportedOperationException();
    }

    //    @ApiStatus.Internal
    public <T> Processor<? super T> inheritCurrentDumbAccessType(@NonNull Processor<? super T> processor) {
        return processor;
    }

    public static FileBasedIndex getInstance() {
        return ApplicationManager.getApplication().getService(FileBasedIndex.class);
    }

    public static int getFileId(@NonNull final VirtualFile file) {
        if (file instanceof VirtualFileWithId) {
            return ((VirtualFileWithId) file).getId();
        }

        return FileIdStorage.getAndStoreId(file);

//        throw new IllegalArgumentException("Virtual file doesn't support id: " +
//                                           file +
//                                           ", implementation class: " +
//                                           file.getClass().getName());
    }
    @Deprecated(forRemoval = true)
    public abstract VirtualFile findFileById(Project project, int id);

    public void requestRebuild(@NonNull ID<?, ?> indexId) {
        requestRebuild(indexId, new Throwable());
    }

    @NonNull
    public abstract <K, V> List<V> getValues(@NonNull ID<K, V> indexId,
                                             @NonNull K dataKey,
                                             @NonNull GlobalSearchScope filter);

    @NonNull
    public abstract <K, V> Collection<VirtualFile> getContainingFiles(@NonNull ID<K, V> indexId,
                                                                      @NonNull K dataKey,
                                                                      @NonNull GlobalSearchScope filter);

    /**
     * @return lazily reified iterator of VirtualFile's.
     */
//    @ApiStatus.Experimental
    @NonNull
    public abstract <K, V> Iterator<VirtualFile> getContainingFilesIterator(@NonNull ID<K, V> indexId,
                                                                            @NonNull K dataKey,
                                                                            @NonNull GlobalSearchScope filter);

    /**
     * @return {@code false} if ValueProcessor.process() returned {@code false}; {@code true}
     * otherwise or if ValueProcessor was not called at all
     */
    public abstract <K, V> boolean processValues(@NonNull ID<K, V> indexId,
                                                 @NonNull K dataKey,
                                                 @Nullable VirtualFile inFile,
                                                 @NonNull ValueProcessor<? super V> processor,
                                                 @NonNull GlobalSearchScope filter);

    /**
     * @return {@code false} if ValueProcessor.process() returned {@code false}; {@code true}
     * otherwise or if ValueProcessor was not called at all
     */
    public <K, V> boolean processValues(@NonNull ID<K, V> indexId,
                                        @NonNull K dataKey,
                                        @Nullable VirtualFile inFile,
                                        @NonNull ValueProcessor<? super V> processor,
                                        @NonNull GlobalSearchScope filter,
                                        @Nullable IdFilter idFilter) {
        return processValues(indexId, dataKey, inFile, processor, filter);
    }

    public abstract <K, V> long getIndexModificationStamp(@NonNull ID<K, V> indexId,
                                                          @NonNull Project project);

    public abstract <K, V> boolean processFilesContainingAllKeys(@NonNull ID<K, V> indexId,
                                                                 @NonNull Collection<? extends K> dataKeys,
                                                                 @NonNull GlobalSearchScope filter,
                                                                 @Nullable Condition<? super V> valueChecker,
                                                                 @NonNull Processor<?
                                                                         super VirtualFile> processor);

    public abstract <K, V> boolean processFilesContainingAnyKey(@NonNull ID<K, V> indexId,
                                                                @NonNull Collection<? extends K> dataKeys,
                                                                @NonNull GlobalSearchScope filter,
                                                                @Nullable IdFilter idFilter,
                                                                @Nullable Condition<? super V> valueChecker,
                                                                @NonNull Processor<?
                                                                        super VirtualFile> processor);

    /**
     * It is guaranteed to return data which is up-to-date within the given project.
     * Keys obtained from the files which do not belong to the project specified may not be
     * up-to-date or even exist.
     */
    @NonNull
    public abstract <K> Collection<K> getAllKeys(@NonNull ID<K, ?> indexId,
                                                 @NonNull Project project);

    /**
     * DO NOT CALL DIRECTLY IN CLIENT CODE
     * The method is internal to indexing engine end is called internally. The method is public
     * due to implementation details
     */
//    @ApiStatus.Internal
    public abstract <K> void ensureUpToDate(@NonNull ID<K, ?> indexId,
                                            @Nullable Project project,
                                            @Nullable GlobalSearchScope filter);

    /**
     * Marks index as requiring rebuild and requests asynchronously full indexing.
     * In unit tests one needs for full effect to dispatch events from event queue
     * with {@code PlatformTestUtil.dispatchAllEventsInIdeEventQueue()}
     */
    public abstract void requestRebuild(@NonNull ID<?, ?> indexId, @NonNull Throwable throwable);

    public abstract <K> void scheduleRebuild(@NonNull ID<K, ?> indexId, @NonNull Throwable e);

    public abstract void requestReindex(@NonNull VirtualFile file);

    public abstract <K, V> boolean getFilesWithKey(@NonNull ID<K, V> indexId,
                                                   @NonNull Set<? extends K> dataKeys,
                                                   @NonNull Processor<? super VirtualFile> processor,
                                                   @NonNull GlobalSearchScope filter);

    /**
     * Executes command and allow its to have an index access in dumb mode.
     * Inside the command it's safe to call index related stuff and
     * {@link IndexNotReadyException} are not expected to be happen
     * here.
     *
     * <p> Please use {@link DumbModeAccessType#ignoreDumbMode(Runnable)} or
     * {@link DumbModeAccessType#ignoreDumbMode(ThrowableComputable)}
     * since they produce less boilerplate code.
     *
     * <p> In smart mode, the behavior is similar to direct command execution
     *
     * @param dumbModeAccessType - defines in which manner command should be executed. Does a
     *                           client expect only reliable data
     * @param command            - a command to execute
     */
//    @ApiStatus.Experimental
    public void ignoreDumbMode(@NonNull DumbModeAccessType dumbModeAccessType,
                               @NonNull Runnable command) {
        ignoreDumbMode(dumbModeAccessType, () -> {
            command.run();
            return null;
        });
    }

    //    @ApiStatus.Experimental
    public <T, E extends Throwable> T ignoreDumbMode(@NonNull DumbModeAccessType dumbModeAccessType,
                                                     @NonNull ThrowableComputable<T, E> computable) throws E {
        throw new UnsupportedOperationException();
    }

    /**
     * It is guaranteed to return data which is up-to-date within the given project.
     */
    public abstract <K> boolean processAllKeys(@NonNull ID<K, ?> indexId,
                                               @NonNull Processor<? super K> processor,
                                               @Nullable Project project);

    public <K> boolean processAllKeys(@NonNull ID<K, ?> indexId,
                                      @NonNull Processor<? super K> processor,
                                      @NonNull GlobalSearchScope scope,
                                      @Nullable IdFilter idFilter) {
        return processAllKeys(indexId, processor, scope.getProject());
    }

    @NonNull
    public abstract <K, V> Map<K, V> getFileData(@NonNull ID<K, V> id,
                                                 @NonNull VirtualFile virtualFile,
                                                 @NonNull Project project);

    public abstract <V> V getSingleEntryIndexData(@NonNull ID<Integer, V> id,
                                                  @NonNull VirtualFile virtualFile,
                                                  @NonNull Project project);

    public static void iterateRecursively(@NonNull final VirtualFile root,
                                          @NonNull final ContentIterator processor,
                                          @Nullable final ProgressIndicator indicator,
                                          @Nullable final Set<? super VirtualFile> visitedRoots,
                                          @Nullable final ProjectFileIndex projectFileIndex) {
        VirtualFileFilter acceptFilter = file -> {
            if (indicator != null) {
                indicator.checkCanceled();
            }
            if (visitedRoots != null &&
                !root.equals(file) &&
                file.isDirectory() &&
                !visitedRoots.add(file)) {
                return false;
            }
            return projectFileIndex == null ||
                   !ReadAction.compute(() -> projectFileIndex.isExcluded(file));
        };

        VirtualFileFilter symlinkFilters = file -> {
            if (acceptFilter.accept(file)) {
                if (file.is(VFileProperty.SYMLINK)) {
                    if (!Registry.is("indexer.follows.symlinks")) {
                        return false;
                    }
                    VirtualFile canonicalFile = file.getCanonicalFile();
                    if (canonicalFile != null) {
                        return acceptFilter.accept(canonicalFile);
                    }
                }
                return true;
            }
            return false;
        };

        VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
            @Override
            public @NonNull Result visitFileEx(@NonNull VirtualFile file) {
                if (!symlinkFilters.accept(file)) {
                    return SKIP_CHILDREN;
                }
                if (!processor.processFile(file)) {
                    return skipTo(root);
                }
                return CONTINUE;
            }
        });
    }

    public void invalidateCaches() {
        throw new IncorrectOperationException();
    }

    /**
     * @return true if input file:
     * <ul>
     * <li> was scanned before indexing of some project in current IDE session </li>
     * <li> contains up-to-date indexed state </li>
     * </ul>
     */

    public boolean isFileIndexedInCurrentSession(@NonNull VirtualFile file,
                                                 @NonNull ID<?, ?> indexId) {
        throw new UnsupportedOperationException();
    }

    public static class AllKeysQuery<K, V> {
        @NonNull
        private final ID<K, V> indexId;
        @NonNull
        private final Collection<? extends K> dataKeys;
        @Nullable
        private final Condition<? super V> valueChecker;

        public AllKeysQuery(@NonNull ID<K, V> id,
                            @NonNull Collection<? extends K> keys,
                            @Nullable Condition<? super V> checker) {
            indexId = id;
            dataKeys = keys;
            valueChecker = checker;
        }

        @NonNull
        public ID<K, V> getIndexId() {
            return indexId;
        }

        @NonNull
        public Collection<? extends K> getDataKeys() {
            return dataKeys;
        }

        @Nullable
        public Condition<? super V> getValueChecker() {
            return valueChecker;
        }
    }

    /**
     * Analogue of
     * {@link FileBasedIndex#processFilesContainingAllKeys(ID, Collection, GlobalSearchScope, Condition, Processor)}
     * which optimized to perform several queries for different indexes.
     */
    public boolean processFilesContainingAllKeys(@NonNull Collection<? extends AllKeysQuery<?, ?>> queries,
                                                 @NonNull GlobalSearchScope filter,
                                                 @NonNull Processor<? super VirtualFile> processor) {
        throw new UnsupportedOperationException();
    }

    @FunctionalInterface
    public interface ValueProcessor<V> {
        /**
         * @param value a value to process
         * @param file  the file the value came from
         * @return {@code false} if no further processing is needed, {@code true} otherwise
         */
        boolean process(@NonNull VirtualFile file, V value);
    }

    @FunctionalInterface
    public interface InputFilter {
        boolean acceptInput(@NonNull VirtualFile file);
    }

    /**
     * An input filter which accepts {@link IndexedFile} as parameter.
     * One could use this interface for filters which require {@link Project} instance to filter
     * out files.
     * <p>
     * Note that in most the cases no one needs this filter.
     * And the only use case is to optimize indexed file count when the corresponding indexer is
     * relatively slow.
     */
    public interface ProjectSpecificInputFilter extends InputFilter {
        @Override
        default boolean acceptInput(@NonNull VirtualFile file) {
//            PluginException.r(getClass(), "acceptInput", "`acceptInput(IndexedFile)` should be
//            called");
            return false;
        }

        boolean acceptInput(@NonNull IndexedFile file);
    }

    /**
     * @see DefaultFileTypeSpecificInputFilter
     */
    public interface FileTypeSpecificInputFilter extends InputFilter {
        void registerFileTypesUsedForIndexing(@NonNull Consumer<? super FileType> fileTypeSink);
    }

    //    @ApiStatus.Internal
    public static final boolean ourSnapshotMappingsEnabled =
            SystemProperties.getBooleanProperty("idea.index.snapshot.mappings.enabled", true);

    //    @ApiStatus.Internal
    public static boolean isIndexAccessDuringDumbModeEnabled() {
        return !ourDisableIndexAccessDuringDumbMode;
    }

    private static final boolean ourDisableIndexAccessDuringDumbMode =
            Boolean.getBoolean("idea.disable.index.access.during.dumb.mode");

    //    @ApiStatus.Internal
    public static final boolean USE_IN_MEMORY_INDEX =
            Boolean.getBoolean("idea.use.in.memory.file.based.index");

    //    @ApiStatus.Internal
    public static final boolean IGNORE_PLAIN_TEXT_FILES =
            Boolean.getBoolean("idea.ignore.plain.text.indexing");
}