package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.fileeditor.api.FileEditorManager;
import com.tyron.fileeditor.api.TextEditor;

import java.io.File;
import java.time.Instant;
import java.util.Objects;

public class RosemoeCodeEditor implements TextEditor {

    private final File mFile;
    private final RosemoeEditorProvider mProvider;
    private final CodeEditorFragment mFragment;

    public RosemoeCodeEditor(File file, RosemoeEditorProvider provider) {
        mFile = file;
        mProvider = provider;
        mFragment = createFragment(file);
    }

    protected CodeEditorFragment createFragment(File file) {
        return CodeEditorFragment.newInstance(file);
    }

    @Override
    public Fragment getFragment() {
        if (mFragment == null || mFragment.getContext() == null || mFragment.isDetached()) {
            FileEditorManagerImpl instance = (FileEditorManagerImpl) FileEditorManagerImpl.getInstance();
            Fragment fragment =
                    instance.getFragmentManager().findFragmentByTag("f" + hashCode());
            if (fragment != null) {
                return fragment;
            }
        }
        return mFragment;
    }

    @Override
    public View getPreferredFocusedView() {
        return mFragment.getView();
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
}
