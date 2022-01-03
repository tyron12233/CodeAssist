package com.tyron.code.ui.editor.impl.image;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.code.ui.editor.api.FileEditor;

import java.io.File;

public class ImageEditor implements FileEditor {

    private final File mFile;
    private final ImageEditorProvider mProvider;
    private ImageEditorFragment mFragment;

    public ImageEditor(@NonNull File file, ImageEditorProvider provider) {
        mFile = file;
        mProvider = provider;
        mFragment = createFragment(file);
    }

    protected ImageEditorFragment createFragment(@NonNull File file) {
        return ImageEditorFragment.newInstance(file);
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
        return "image-editor";
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
