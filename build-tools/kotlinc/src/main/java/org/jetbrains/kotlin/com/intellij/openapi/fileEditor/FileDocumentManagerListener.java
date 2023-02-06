package org.jetbrains.kotlin.com.intellij.openapi.fileEditor;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.EventListener;

/**
 * Listener for {@link VirtualFile} to {@link Document} association events:
 * Virtual file loading, Document creation, Document saving back and reloading.
 * @see AppTopics#FILE_DOCUMENT_SYNC
 */
public interface FileDocumentManagerListener extends EventListener {

    /**
     * There is a possible case that callback that listens for the events implied by the current interface needs to modify document
     * contents (e.g. strip trailing spaces before saving a document). It's too dangerous to do that from message bus callback
     * because that may cause unexpected 'nested modification' (see IDEA-71701 for more details).
     * <p/>
     * That's why this interface is exposed via extension point as well - it's possible to modify document content from
     * the extension callback.
     */
    ExtensionPointName<FileDocumentManagerListener> EP_NAME = new ExtensionPointName<>("com.intellij.fileDocumentManagerListener");

    /**
     * Fired before processing FileDocumentManager.saveAllDocuments(). Can be used by plugins
     * which need to perform additional save operations when documents, rather than settings,
     * are saved.
     */
    default void beforeAllDocumentsSaving() {
    }

    /**
     * NOTE: Vetoing facility is deprecated in this listener implement {@link FileDocumentSynchronizationVetoer} instead.
     */
    default void beforeDocumentSaving(@NonNull Document document) {
    }

    /**
     * NOTE: Vetoing facility is deprecated in this listener implement {@link FileDocumentSynchronizationVetoer} instead.
     */
    default void beforeFileContentReload(@NonNull VirtualFile file, @NonNull Document document) {
    }

    default void fileWithNoDocumentChanged(@NonNull VirtualFile file) {
    }

    default void fileContentReloaded(@NonNull VirtualFile file, @NonNull Document document) {
    }

    default void fileContentLoaded(@NonNull VirtualFile file, @NonNull Document document) {
    }

    default void unsavedDocumentDropped(@NonNull Document document) {
        unsavedDocumentsDropped();
    }

    default void unsavedDocumentsDropped() {
    }

    default void afterDocumentUnbound(@NonNull VirtualFile file, @NonNull Document document) {
    }
}