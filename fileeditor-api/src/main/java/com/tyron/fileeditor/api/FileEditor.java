package com.tyron.fileeditor.api;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.io.File;

/**
 * Represents the Tab view and can contain different views based on it's function
 */
public interface FileEditor {

    /**
     * @return the fragment which represents the editor in UI
     */
    Fragment getFragment();

    /**
     * @return the view to be focused when this editor has been selected.
     */
    View getPreferredFocusedView();

    /**
     * @return The name of this editor to be distinguished from other editors.
     * Note that this is not the name displayed from UI
     */
    @NonNull
    String getName();

    /**
     * @return whether this editor has been modified in comparison with its file
     */
    boolean isModified();

    /**
     * @return whether this editor is valid or not. An editor may become invalid
     * if the file it represents have been deleted.
     */
    boolean isValid();

    /**
     * @return the file this editor is editing
     */
    File getFile();
}
