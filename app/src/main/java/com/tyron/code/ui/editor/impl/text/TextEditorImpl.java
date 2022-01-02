package com.tyron.code.ui.editor.impl.text;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.api.TextEditor;

import java.io.File;

public class TextEditorImpl implements TextEditor {

    protected final Project mProject;
    private final TextEditorFragment mFragment;
    private final TextEditorProvider mProvider;

    public TextEditorImpl(Project project, File file, TextEditorProvider provider) {
        mProject= project;
        mProvider = provider;
        mFragment = createEditor(project, file);
    }

    protected TextEditorFragment createEditor(final Project project, final File file) {
        return TextEditorFragment.newInstance(project, file);
    }

    @Override
    public Fragment getFragment() {
        return mFragment;
    }

    @Override
    public View getPreferredFocusedView() {
        return mFragment.getView();
    }

    @NonNull
    @Override
    public String getName() {
        return "Text";
    }

    @Override
    public boolean isModified() {
        return mFragment.isModified();
    }

    @Override
    public boolean isValid() {
        return mFragment.isValid();
    }
}
