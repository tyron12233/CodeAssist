package com.tyron.code.ui.editor.impl.image;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.tyron.fileeditor.api.FileEditor;

import java.io.File;
import java.util.Objects;

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
        return "Image Editor";
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public File getFile() {
        return mFile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ImageEditor that = (ImageEditor) o;
        return Objects.equals(mFile, that.mFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFile);
    }
}
