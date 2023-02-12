package org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.ide.plugins.IdeaPluginDescriptor;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuard;
import org.jetbrains.kotlin.com.intellij.openapi.application.TransactionGuardImpl;
import org.jetbrains.kotlin.com.intellij.openapi.application.WriteAction;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentEvent;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.PrioritizedDocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtilRt;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl;
import org.jetbrains.kotlin.com.intellij.psi.AbstractFileViewProvider;
import org.jetbrains.kotlin.com.intellij.psi.ExternalChangeAction;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.kotlin.com.intellij.util.ExceptionUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.messages.Topic;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class FileDocumentManagerImpl extends FileDocumentManagerBase {

    private static final Logger LOG = Logger.getInstance(FileDocumentManagerImpl.class);

    public static final Key<Object> NOT_RELOADABLE_DOCUMENT_KEY = new Key<>("NOT_RELOADABLE_DOCUMENT_KEY");

    private static final Key<String> LINE_SEPARATOR_KEY = Key.create("LINE_SEPARATOR_KEY");
    private static final Key<Boolean> MUST_RECOMPUTE_FILE_TYPE = Key.create("Must recompute file type");
    private static final Topic<FileDocumentManagerListener> FILE_DOCUMENT_SYNC = Topic.create("fileDocumentSync", FileDocumentManagerListener.class);

    private final Set<Document> myUnsavedDocuments = Collections.synchronizedSet(new HashSet<>());

    private final FileDocumentManagerListener myMultiCaster;
//    private final TrailingSpacesStripper myTrailingSpacesStripper = new TrailingSpacesStripper();

    private boolean myOnClose;

    private final PrioritizedDocumentListener myPhysicalDocumentChangeTracker = new PrioritizedDocumentListener() {

        @Override
        public int getPriority() {
            return Integer.MIN_VALUE;
        }

        @Override
        public void documentChanged(@NonNull DocumentEvent e) {
            Document document = e.getDocument();
//            if (!ApplicationManager.getApplication().hasWriteAction(ExternalChangeAction.ExternalDocumentChange.class)) {
                myUnsavedDocuments.add(document);
//            }
            Runnable currentCommand = CommandProcessor.getInstance().getCurrentCommand();
//            Project project = currentCommand == ?null ? null : CommandProcessor.getInstance().getCurrentCommandProject();
//            if (project == null) {
//                VirtualFile virtualFile = getFile(document);
//                project = virtualFile == null ? null : ProjectUtil.guessProjectForFile(virtualFile);
//            }
//            String lineSeparator = CodeStyle.getProjectOrDefaultSettings(project).getLineSeparator();
//            document.putUserData(LINE_SEPARATOR_KEY, lineSeparator);
//
//            // avoid documents piling up during batch processing
            if (areTooManyDocumentsInTheQueue(myUnsavedDocuments)) {
                saveAllDocumentsLater();
            }
        }
    };

    public FileDocumentManagerImpl() {
        InvocationHandler handler = (__, method, args) -> {
            if (method.getDeclaringClass() != FileDocumentManagerListener.class) {
                // only FileDocumentManagerListener methods should be called on this proxy
                throw new UnsupportedOperationException(method.toString());
            }
            multiCast(method, args);
            return null;
        };

        ClassLoader loader = FileDocumentManagerListener.class.getClassLoader();
        myMultiCaster = (FileDocumentManagerListener) Proxy.newProxyInstance(loader, new Class[]{FileDocumentManagerListener.class}, handler);

        // remove VirtualFiles sitting in the DocumentImpl.rmTreeQueue reference queue which could retain plugin-registered FS in their VirtualDirectoryImpl.myFs
//        ApplicationManager.getApplication().getMessageBus().connect().subscribe(DynamicPluginListener.TOPIC, new DynamicPluginListener() {
//            @Override
//            public void pluginUnloaded(@NonNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
//                DocumentImpl.processQueue();
//            }
//        });
    }

    private static void unwrapAndRethrow(@NonNull Exception e) {
        Throwable unwrapped = e;
        if (e instanceof InvocationTargetException) {
            unwrapped = e.getCause() == null ? e : e.getCause();
        }
        ExceptionUtil.rethrowUnchecked(unwrapped);
        LOG.error(unwrapped);
    }

    @SuppressWarnings("OverlyBroadCatchBlock")
    private void multiCast(@NonNull Method method, Object[] args) {
        try {
            method.invoke(ApplicationManager.getApplication().getMessageBus().syncPublisher(FILE_DOCUMENT_SYNC), args);
        }
        catch (ClassCastException e) {
            LOG.error("Arguments: " + Arrays.toString(args), e);
        }
        catch (Exception e) {
            unwrapAndRethrow(e);
        }

        // Allows pre-save document modification
        for (FileDocumentManagerListener listener : getListeners()) {
            try {
                method.invoke(listener, args);
            }
            catch (Exception e) {
                unwrapAndRethrow(e);
            }
        }

        // stripping trailing spaces
        try {
//            method.invoke(myTrailingSpacesStripper, args);
        }
        catch (Exception e) {
            unwrapAndRethrow(e);
        }
    }

    public static boolean areTooManyDocumentsInTheQueue(@NonNull Collection<? extends Document> documents) {
        if (documents.size() > 100) return true;
        int totalSize = 0;
        for (Document document : documents) {
            totalSize += document.getTextLength();
            if (totalSize > FileUtilRt.LARGE_FOR_CONTENT_LOADING) return true;
        }
        return false;
    }

    @Override
    @NonNull
    protected Document createDocument(@NonNull CharSequence text, @NonNull VirtualFile file) {
        boolean acceptSlashR = file instanceof LightVirtualFile && StringUtil.indexOf(text, '\r') >= 0;
        boolean freeThreaded = Boolean.TRUE.equals(file.getUserData(AbstractFileViewProvider.FREE_THREADED));
        DocumentImpl document = new DocumentImpl(text, acceptSlashR, freeThreaded);
//        Project project = ProjectUtil.guessProjectForFile(file);
//        int tabSize = project == null ? CodeStyle.getDefaultSettings().getTabSize(file.getFileType())  : CodeStyle.getFacade(project, document, file.getFileType()).getTabSize();
        int tabSize = 4;
        // calculate and pass tab size here since it's the ony place we have access to CodeStyle.
        // tabSize might be needed by PersistentRangeMarkers to be able to restore from (line;col) info to offset
//        document.documentCreatedFrom(file, tabSize);
        return document;
    }

    @Override
    protected void fileContentLoaded(@NonNull VirtualFile file, @NonNull Document document) {
        myMultiCaster.fileContentLoaded(file, document);
    }

    @NonNull
    @Override
    protected DocumentListener getDocumentListener() {
        return myPhysicalDocumentChangeTracker;
    }


    public void dropAllUnsavedDocuments() {
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            throw new RuntimeException("This method is only for test mode!");
        }
        ApplicationManager.getApplication().assertWriteAccessAllowed();
        if (!myUnsavedDocuments.isEmpty()) {
            for (Document document : myUnsavedDocuments) {
                VirtualFile file = getFile(document);
                if (file == null) continue;
                unbindFileFromDocument(file, document);
            }
            myUnsavedDocuments.clear();
            myMultiCaster.unsavedDocumentsDropped();
        }
    }

    private static Project guessProjectForFile(VirtualFile file) {
        return ProjectCoreUtil.theOnlyOpenProject();
    }

    private void saveAllDocumentsLater() {
        // later because some document might have been blocked by PSI right now
        ApplicationManager.getApplication().invokeLater(() -> {
            Document[] unsavedDocuments = getUnsavedDocuments();
            for (Document document : unsavedDocuments) {
                VirtualFile file = getFile(document);
                if (file == null) continue;
                Project project = guessProjectForFile(file);
                if (project == null) continue;
                if (PsiDocumentManager.getInstance(project).isDocumentBlockedByPsi(document)) continue;

                saveDocument(document);
            }
        });
    }

    public void saveAllDocuments() {
        saveAllDocuments(true);
    }

    /**
     * @param isExplicit caused by user directly (Save action) or indirectly (e.g. Compile)
     */
    public void saveAllDocuments(boolean isExplicit) {
        saveDocuments(null, isExplicit);
    }

    public void saveDocuments(@NonNull Predicate<? super Document> filter) {
        saveDocuments(filter, true);
    }

    private void saveDocuments(@Nullable Predicate<? super Document> filter, boolean isExplicit) {
//        ApplicationManager.getApplication().assertIsDispatchThread();
        ((TransactionGuardImpl) TransactionGuard.getInstance()).assertWriteActionAllowed();

        myMultiCaster.beforeAllDocumentsSaving();
        if (myUnsavedDocuments.isEmpty()) return;

        Map<Document, IOException> failedToSave = new HashMap<>();
        Set<Document> vetoed = new HashSet<>();
        while (true) {
            int count = 0;

            for (Document document : myUnsavedDocuments) {
                if (filter != null && !filter.test(document)) continue;
                if (failedToSave.containsKey(document)) continue;
                if (vetoed.contains(document)) continue;
                try {
                    doSaveDocument(document, isExplicit);
                }
                catch (IOException e) {
                    failedToSave.put(document, e);
                }
                catch (SaveVetoException e) {
                    vetoed.add(document);
                }
                count++;
            }

            if (count == 0) break;
        }

        if (!failedToSave.isEmpty()) {
            handleErrorsOnSave(failedToSave);
        }
    }

    @Override
    public void saveDocument(@NonNull Document document) {
        saveDocument(document, true);
    }

    public void saveDocument(@NonNull Document document, boolean explicit) {
//        ApplicationManager.getApplication().assertIsDispatchThread();
        ((TransactionGuardImpl)TransactionGuard.getInstance()).assertWriteActionAllowed();

        if (!myUnsavedDocuments.contains(document)) return;

        try {
            doSaveDocument(document, explicit);
        }
        catch (IOException e) {
            handleErrorsOnSave(Collections.singletonMap(document, e));
        }
        catch (SaveVetoException ignored) {
        }
    }

    private void handleErrorsOnSave(@NonNull Map<Document, IOException> failures) {

    }

    @Override
    public void saveDocumentAsIs(@NonNull Document document) {
        VirtualFile file = getFile(document);
        boolean spaceStrippingEnabled = true;
        if (file != null) {
//            spaceStrippingEnabled = TrailingSpacesStripper.isEnabled(file);
//            TrailingSpacesStripper.setEnabled(file, false);
        }
        try {
            saveDocument(document);
        }
        finally {
            if (file != null) {
//                TrailingSpacesStripper.setEnabled(file, spaceStrippingEnabled);
            }
        }
    }

    private void doSaveDocument(@NonNull Document document, boolean isExplicit) throws IOException, SaveVetoException {
        VirtualFile file = getFile(document);
        if (LOG.isTraceEnabled()) LOG.trace("saving: " + file);

        if (file == null ||
            !isTrackable(file) ||
            file.isValid() && !isFileModified(file)) {
            removeFromUnsaved(document);
            return;
        }

        if (file.isValid() && needsRefresh(file)) {
            LOG.trace("  refreshing...");
            file.refresh(false, false);
            if (!myUnsavedDocuments.contains(document)) return;
        }

        if (!maySaveDocument(file, document, isExplicit)) {
            throw new SaveVetoException();
        }

        LOG.trace("  writing...");
        WriteAction.run(() -> doSaveDocumentInWriteAction(document, file));
        LOG.trace("  done");
    }

    private boolean maySaveDocument(@NonNull VirtualFile file, @NonNull Document document, boolean isExplicit) {
        return true;
//        return !myConflictResolver.hasConflict(file) &&
//               FileDocumentSynchronizationVetoer.EP_NAME.getExtensionList().stream().allMatch(vetoer -> vetoer.maySaveDocument(document, isExplicit));
    }

    private void doSaveDocumentInWriteAction(@NonNull Document document, @NonNull VirtualFile file) throws IOException {
        if (!file.isValid()) {
            removeFromUnsaved(document);
            return;
        }

        if (!file.equals(getFile(document))) {
            registerDocument(document, file);
        }

        boolean saveNeeded = false;
        Exception ioException = null;
        try {
            saveNeeded = isSaveNeeded(document, file);
        }
        catch (IOException|RuntimeException e) {
            // in case of corrupted VFS try to stay consistent
            ioException = e;
        }
        if (!saveNeeded) {
            if (document instanceof DocumentEx) {
                ((DocumentEx)document).setModificationStamp(file.getModificationStamp());
            }
            removeFromUnsaved(document);
            updateModifiedProperty(file);
            if (ioException instanceof IOException) throw (IOException)ioException;
            if (ioException != null) throw (RuntimeException)ioException;
            return;
        }

        Runnable runnable = () -> {
            myMultiCaster.beforeDocumentSaving(document);
            LOG.assertTrue(file.isValid());

            String text = document.getText();
            String lineSeparator = getLineSeparator(document, file);
            if (!lineSeparator.equals("\n")) {
                text = StringUtil.convertLineSeparators(text, lineSeparator);
            }

            Project project = guessProjectForFile(file);
            try {
                LoadTextUtil.write(project, file, this, text, document.getModificationStamp());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            myUnsavedDocuments.remove(document);
            LOG.assertTrue(!myUnsavedDocuments.contains(document));
//            myTrailingSpacesStripper.clearLineModificationFlags(document);
        };

        runnable.run();
    }

    private static void updateModifiedProperty(@NonNull VirtualFile file) {
//        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
//            FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
//            for (FileEditor editor : fileEditorManager.getAllEditors(file)) {
//                if (editor instanceof TextEditorImpl) {
//                    ((TextEditorImpl)editor).updateModifiedProperty();
//                }
//            }
//        }
    }

    private void removeFromUnsaved(@NonNull Document document) {
        myUnsavedDocuments.remove(document);
        myMultiCaster.unsavedDocumentDropped(document);
        LOG.assertTrue(!myUnsavedDocuments.contains(document));
    }

    private static boolean isSaveNeeded(@NonNull Document document, @NonNull VirtualFile file) throws IOException {
        if (file.getFileType().isBinary() || document.getTextLength() > 1000 * 1000) {    // don't compare if the file is too big
            return true;
        }

        byte[] bytes = file.contentsToByteArray();
        CharSequence loaded = LoadTextUtil.getTextByBinaryPresentation(bytes, file, false, false);

        return !Comparing.equal(document.getCharsSequence(), loaded);
    }

    private static boolean needsRefresh(@NonNull VirtualFile file) {
        return true;
//        VirtualFileSystem fs = file.getFileSystem();
//        return fs instanceof NewVirtualFileSystem && file.getTimeStamp() != ((NewVirtualFileSystem)fs).getTimeStamp(file);
    }

    @NonNull
    public static String getLineSeparator(@NonNull Document document, @NonNull VirtualFile file) {
        String lineSeparator = file.getDetectedLineSeparator();
        if (lineSeparator == null) {
            lineSeparator = document.getUserData(LINE_SEPARATOR_KEY);
            if (lineSeparator == null) {
                lineSeparator = "\n";
            }
        }
        return lineSeparator;
    }

    @Override
    @NonNull
    public String getLineSeparator(@Nullable VirtualFile file, @Nullable Project project) {
        String lineSeparator = file == null ? null : file.getDetectedLineSeparator();
        if (lineSeparator == null) {
//            lineSeparator = CodeStyle.getProjectOrDefaultSettings(project).getLineSeparator();
            lineSeparator = "\n";
        }
        return lineSeparator;
    }

    public void reloadFiles(VirtualFile ... files) {
        for (VirtualFile file : files) {
            if (file.exists()) {
                Document doc = getCachedDocument(file);
                if (doc != null) {
                    reloadFromDisk(doc);
                }
            }
        }
    }

    public Document [] getUnsavedDocuments() {
        if (myUnsavedDocuments.isEmpty()) {
            return Document.EMPTY_ARRAY;
        }

        List<Document> list = new ArrayList<>(myUnsavedDocuments);
        return list.toArray(Document.EMPTY_ARRAY);
    }

    @Override
    public boolean isDocumentUnsaved(@NonNull Document document) {
        return myUnsavedDocuments.contains(document);
    }

    @Override
    public void reloadFromDisk(@NonNull Document document) {
        throw new UnsupportedOperationException();
    }

    //    @Override
    public boolean isFileModified(@NonNull VirtualFile file) {
//        ModelBranch branch = ModelBranch.getFileBranch(file);
//        if (branch != null && ((ModelBranchImpl)branch).hasModifications(file)) {
//            return true;
//        }
        Document doc = getCachedDocument(file);
        return doc != null && isDocumentUnsaved(doc) && doc.getModificationStamp() != file.getModificationStamp();
    }

    @NonNull
    private static List<FileDocumentManagerListener> getListeners() {
        return FileDocumentManagerListener.EP_NAME.getExtensionList();
    }

    private static class SaveVetoException extends Exception {}
}
