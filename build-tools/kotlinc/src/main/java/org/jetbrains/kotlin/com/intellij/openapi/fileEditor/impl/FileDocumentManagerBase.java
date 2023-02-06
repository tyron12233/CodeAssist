package org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.FrozenDocument;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtilRt;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.NonPhysicalFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.encoding.EncodingManager;
import org.jetbrains.kotlin.com.intellij.psi.FileViewProvider;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.kotlin.com.intellij.util.FileContentUtilCore;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

public abstract class FileDocumentManagerBase extends FileDocumentManager {
    public static final Key<Document> HARD_REF_TO_DOCUMENT_KEY =
            Key.create("HARD_REF_TO_DOCUMENT_KEY");
    public static final Key<Boolean> TRACK_NON_PHYSICAL = Key.create("TRACK_NON_PHYSICAL");

    private static final Key<VirtualFile> FILE_KEY = Key.create("FILE_KEY");
    private static final Key<Boolean> BIG_FILE_PREVIEW = Key.create("BIG_FILE_PREVIEW");
    private static final Object lock = new Object();

    public static boolean isTrackable(@NonNull VirtualFile file) {
        return !(file.getFileSystem() instanceof NonPhysicalFileSystem) ||
               Boolean.TRUE.equals(file.getUserData(TRACK_NON_PHYSICAL));
    }

    protected static boolean isBinaryWithoutDecompiler(@NonNull VirtualFile file) {
        FileType type = file.getFileType();
        return type.isBinary() && BinaryFileTypeDecompilers.getInstance().forFileType(type) == null;
    }

    protected static int getPreviewCharCount(@NonNull VirtualFile file) {
        Charset charset = EncodingManager.getInstance().getEncoding(file, false);
        float bytesPerChar = charset == null ? 2 : charset.newEncoder().averageBytesPerChar();
        return (int) (FileUtilRt.LARGE_FILE_PREVIEW_SIZE / bytesPerChar);
    }

    @Override
    public @Nullable Document getDocument(@NonNull VirtualFile file) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        DocumentEx document = (DocumentEx) getCachedDocument(file);
        if (document == null) {
            if (!file.isValid() || file.isDirectory() || isBinaryWithoutDecompiler(file)) {
                return null;
            }

            boolean tooLarge = FileUtilRt.isTooLarge(file.getLength());
            if (file.getFileType().isBinary() && tooLarge) {
                return null;
            }

            CharSequence text = loadText(file, tooLarge);
            synchronized (lock) {
                document = (DocumentEx) getCachedDocument(file);
                if (document != null) {
                    return document;  // double-checking
                }

                document = (DocumentEx) createDocument(text, file);
                document.setModificationStamp(file.getModificationStamp());

                if (isTrackable(file)) {
                    document.addDocumentListener(getDocumentListener());
                }

                cacheDocument(file, document);
            }
        }
        return document;
    }

    protected static void setDocumentTooLarge(Document document, boolean tooLarge) {
        document.putUserData(BIG_FILE_PREVIEW, tooLarge ? Boolean.TRUE : null);
    }

    private @NonNull CharSequence loadText(@NonNull VirtualFile file, boolean tooLarge) {
        if (file instanceof LightVirtualFile) {
            FileViewProvider vp = findCachedPsiInAnyProject(file);
            if (vp != null) {
                return vp.getPsi(vp.getBaseLanguage()).getText();
            }
        }

        return tooLarge ? LoadTextUtil.loadText(file,
                getPreviewCharCount(file)) : LoadTextUtil.loadText(file);
    }

    protected abstract @NonNull Document createDocument(@NonNull CharSequence text, @NonNull VirtualFile file);

    @Override
    public @Nullable Document getCachedDocument(@NonNull VirtualFile file) {
        Document hard = file.getUserData(HARD_REF_TO_DOCUMENT_KEY);
        return hard != null ? hard : getDocumentFromCache(file);
    }

    /**
     * Storing file<->document association with hard references to avoid undesired GCs.
     * Works for non-physical ViewProviders only, to avoid memory leaks.
     * Please do not use under the penalty of severe memory leaks and wild PSI inconsistencies.
     */
    public static void registerDocument(@NonNull Document document, @NonNull VirtualFile virtualFile) {
        if (!(virtualFile instanceof LightVirtualFile) &&
            !(virtualFile.getFileSystem() instanceof NonPhysicalFileSystem)) {
            throw new IllegalArgumentException(
                    "Hard-coding file<->document association is permitted for non-physical files only (see FileViewProvider.isPhysical())" +
                    " to avoid memory leaks. virtualFile=" + virtualFile);
        }
        synchronized (lock) {
            document.putUserData(FILE_KEY, virtualFile);
            virtualFile.putUserData(HARD_REF_TO_DOCUMENT_KEY, document);
        }
    }

    @Override
    public @Nullable VirtualFile getFile(@NonNull Document document) {
        return document instanceof FrozenDocument ? null : document.getUserData(FILE_KEY);
    }

    @Override
    public void reloadBinaryFiles() {
        List<VirtualFile> binaries = ContainerUtil.filter(myDocumentCache.keySet(), file -> file.getFileType().isBinary());
        FileContentUtilCore.reparseFiles(binaries);
    }

//    @Override
    public boolean isPartialPreviewOfALargeFile(@NonNull Document document) {
        return document.getUserData(BIG_FILE_PREVIEW) == Boolean.TRUE;
    }

    void unbindFileFromDocument(@NonNull VirtualFile file, @NonNull Document document) {
        removeDocumentFromCache(file);
        file.putUserData(HARD_REF_TO_DOCUMENT_KEY, null);
        document.putUserData(FILE_KEY, null);
    }

    private final Map<VirtualFile, Document> myDocumentCache = ContainerUtil.createConcurrentWeakValueMap();

    private void cacheDocument(@NonNull VirtualFile file, @NonNull Document document) {
        myDocumentCache.put(file, document);
    }

    private void removeDocumentFromCache(@NonNull VirtualFile file) {
        myDocumentCache.remove(file);
    }

    private Document getDocumentFromCache(@NonNull VirtualFile file) {
        return myDocumentCache.get(file);
    }

    protected void clearDocumentCache() {
        myDocumentCache.clear();
    }

    protected abstract void fileContentLoaded(@NonNull VirtualFile file, @NonNull Document document);

    protected abstract @NonNull DocumentListener getDocumentListener();
}
