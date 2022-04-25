package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.editor.Content;
import com.tyron.fileeditor.api.FileDocumentManager;
import com.tyron.fileeditor.api.FileEditorManager;
import com.tyron.fileeditor.api.TextEditor;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

public class RosemoeCodeEditor implements TextEditor {

    private final File mFile;
    private final RosemoeEditorProvider mProvider;
    private final RosemoeEditorFacade mEditor;

    public RosemoeCodeEditor(Context context, File file, RosemoeEditorProvider provider) {
        mFile = file;
        mProvider = provider;
        try {
            mEditor = createEditor(context, VFS.getManager().resolveFile(file.toURI()));
        } catch (FileSystemException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Content getContent() {
        return mEditor.getContent();
    }

    protected CodeEditorFragment createFragment(File file) {
        return CodeEditorFragment.newInstance(file);
    }

    @Override
    public View getView() {
        return mEditor.getView();
    }

    @Override
    public View getPreferredFocusedView() {
        return mEditor.getView();
    }

    @NonNull
    @Override
    public String getName() {
        return "Rosemoe Code Editor";
    }

    @Override
    public boolean isModified() {
        Project project = ProjectManager.getInstance().getCurrentProject();
        if (project != null) {
            Module module = project.getModule(mFile);
            if (module != null) {
                Instant diskModified = Instant.ofEpochMilli(mFile.lastModified());
                Instant lastModified = module.getFileManager().getLastModified(mFile);
                if (lastModified != null) {
                    return lastModified.isAfter(diskModified);
                }
            }
        }
        return false;
    }

    @Override
    public boolean isValid() {
        return mFile.exists();
    }

    @Override
    public File getFile() {
        return mFile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RosemoeCodeEditor that = (RosemoeCodeEditor) o;
        return Objects.equals(mFile, that.mFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFile);
    }

    private static RosemoeEditorFacade createEditor(Context context, FileObject file) {
        try {
            Content content = FileDocumentManager.getInstance().getContent(file);
            return new RosemoeEditorFacade(context, content, file);
        } catch (FileSystemException e) {
            throw new RuntimeException(e);
        }
    }
}
