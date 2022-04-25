package com.tyron.fileeditor.api;

import com.tyron.editor.Content;
import com.tyron.fileeditor.api.impl.FileDocumentManagerImpl;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class FileDocumentManager {

    private static FileDocumentManagerImpl INSTANCE = null;

    public static FileDocumentManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new FileDocumentManagerImpl();
        }
        return INSTANCE;
    }

    @Nullable
    public abstract Content getContent(@NotNull FileObject file) throws FileSystemException;

    @Nullable
    public abstract Content getCachedDocument(@NotNull FileObject file);

    /**
     * Returns the virtual file corresponding to the specified document.
     *
     * @param content the content for which the virtual file is requested.
     * @return the file, or null if the content wasn't created from a virtual file.
     */
    @Nullable
    public abstract FileObject getFile(@NotNull Content content);

    /**
     * Saves all unsaved documents to disk. This operation can modify documents that will be saved
     * (due to 'Strip trailing spaces on Save' functionality). When saving, {@code \n} line separators are converted into
     * the ones used normally on the system, or the ones explicitly specified by the user. Encoding settings are honored.<p/>
     *
     * Should be invoked on the event dispatch thread.
     */
    public abstract void saveAllContents();

    /**
     * Saves the specified document to disk. This operation can modify the document (due to 'Strip
     * trailing spaces on Save' functionality). When saving, {@code \n} line separators are converted into
     * the ones used normally on the system, or the ones explicitly specified by the user. Encoding settings are honored.<p/>
     *
     * Should be invoked on the event dispatch thread.
     * @param content the document to save.
     */
    public abstract void saveContent(@NotNull Content content);

    public abstract boolean isContentUnsaved(@NotNull Content content);
}
