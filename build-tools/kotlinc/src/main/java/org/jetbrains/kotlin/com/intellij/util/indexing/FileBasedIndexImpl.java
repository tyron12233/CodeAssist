package org.jetbrains.kotlin.com.intellij.util.indexing;

import static java.util.concurrent.TimeUnit.SECONDS;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.ManagingFS;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentTransactionListener;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBus;
import org.jetbrains.kotlin.com.intellij.util.messages.SimpleMessageBusConnection;

import java.lang.ref.WeakReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntPredicate;

public class FileBasedIndexImpl extends FileBasedIndexEx {

    private static final ThreadLocal<VirtualFile> ourIndexedFile = new ThreadLocal<>();
    private static final ThreadLocal<IndexWritingFile> ourWritingIndexFile = new ThreadLocal<>();
    private static final ThreadLocal<VirtualFile> ourFileToBeIndexed = new ThreadLocal<>();

    private static final boolean FORBID_LOOKUP_IN_NON_CANCELLABLE_SECTIONS =
            SystemProperties.getBooleanProperty("forbid.index.lookup.in.non.cancellable.section", false);

    @ApiStatus.Internal
    public static final Logger LOG = Logger.getInstance(FileBasedIndexImpl.class);

    private static final boolean USE_GENTLE_FLUSHER = SystemProperties.getBooleanProperty("indexes.flushing.use-gentle-flusher", false);
    /** How often, on average, flush each index to the disk */
    private static final long FLUSHING_PERIOD_MS = SECONDS.toMillis(5);
    private final Lock myReadLock;
    private final Lock myWriteLock;
    private final FileDocumentManager myFileDocumentManager;
    private final boolean myIsUnitTestMode;

//    private RegisteredIndexes myRegisteredIndexes;

    public FileBasedIndexImpl() {
        ReadWriteLock lock = new ReentrantReadWriteLock();
        myReadLock = lock.readLock();
        myWriteLock = lock.writeLock();

        myFileDocumentManager = FileDocumentManager.getInstance();
        myIsUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();

        MessageBus messageBus = ApplicationManager.getApplication().getMessageBus();
        SimpleMessageBusConnection connection = messageBus.simpleConnect();

        connection.subscribe(PsiDocumentTransactionListener.TOPIC, new PsiDocumentTransactionListener() {
            @Override
            public void transactionStarted(@NotNull Document document, @NotNull PsiFile psiFile) {

            }

            @Override
            public void transactionCompleted(@NotNull Document document, @NotNull PsiFile file) {

            }
        });
    }

    private IndexConfiguration getState() {
//        return myRegisteredIndexes.getState();
        return null;
    }


    @Nullable
    @Override
    public VirtualFile getFileBeingCurrentlyIndexed() {
        return ourIndexedFile.get();
    }

    @Nullable
    @Override
    public IndexWritingFile getFileWritingCurrentlyIndexes() {
        return ourWritingIndexFile.get();
    }

    @Override
    public VirtualFile findFileById(Project project, int id) {
        return ManagingFS.getInstance().findFileById(id);
    }

    @Override
    public void requestRebuild(@NonNull ID<?, ?> indexId, @NonNull Throwable throwable) {
        LOG.info("Requesting index rebuild for: " + indexId.getName(), throwable);

    }

    @Override
    public void requestReindex(@NonNull VirtualFile file) {

    }

    @NonNull
    @Override
    public IntPredicate getAccessibleFileIdFilter(@Nullable Project project) {
        return null;
    }

    @Nullable
    @Override
    public IdFilter extractIdFilter(@Nullable GlobalSearchScope scope, @Nullable Project project) {
        return null;
    }

    @Nullable
    @Override
    public IdFilter projectIndexableFiles(@Nullable Project project) {
        return null;
    }

    @NonNull
    @Override
    public <K, V> UpdatableIndex<K, V, FileContent, ?> getIndex(ID<K, V> indexId) {
        UpdatableIndex<K, V, FileContent, ?> index = getState().getIndex(indexId);
        if (index == null) {
            Throwable initializationProblem = getState().getInitializationProblem(indexId);
            String message = "Index is not created for `" + indexId.getName() + "`";
            throw initializationProblem != null
                    ? new IllegalStateException(message, initializationProblem)
                    : new IllegalStateException(message);
        }
        return index;
    }

    @Override
    public void waitUntilIndicesAreInitialized() {

    }

    @Override
    public <K> boolean ensureUpToDate(@NonNull ID<K, ?> indexId,
                                      @Nullable Project project,
                                      @Nullable GlobalSearchScope filter,
                                      @Nullable VirtualFile restrictedFile) {
        return false;
    }

    @Nullable
    @Override
    public VirtualFile findFileById(int id) {
        return ManagingFS.getInstance().findFileById(id);
    }

    @NonNull
    @Override
    public Logger getLogger() {
        return LOG;
    }

    private static final Key<WeakReference<Pair<FileContentImpl, Long>>> ourFileContentKey = Key.create("unsaved.document.index.content");

    // returns false if doc was not indexed because it is already up to date
    // return true if document was indexed
    // caller is responsible to ensure no concurrent same document processing
    void indexUnsavedDocument(@NotNull final Document document,
                              @NotNull final ID<?, ?> requestedIndexId,
                              @NotNull Project project,
                              @NotNull final VirtualFile vFile) {
//        PsiFile dominantContentFile = findLatestKnownPsiForUncomittedDocument(document, project);
//
//        DocumentContent content = findLatestContent(document, dominantContentFile);
    }
}

