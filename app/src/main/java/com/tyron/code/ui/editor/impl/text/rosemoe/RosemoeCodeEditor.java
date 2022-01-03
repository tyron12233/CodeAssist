package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.code.ui.editor.api.TextEditor;

import java.io.File;

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
        return mFragment;
    }

    @Override
    public View getPreferredFocusedView() {
        return mFragment.getView();
    }

    @NonNull
    @Override
    public String getName() {
        return "rosemoe-code-editor";
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }
}
