package com.tyron.layoutpreview2.view;

import android.view.View;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview2.ViewManager;

/**
 * Represents a View in the editor.
 */
public interface EditorView {

    /**
     * Return the view this represents
     *
     * @return The view instance
     */
    @NonNull
    View getAsView();

    /**
     * Return the {@link ViewManager} instance used for updating this view.
     *
     * @return the view manager instance
     */
    @NonNull
    ViewManager getViewManager();

    void setViewManager(@NonNull ViewManager manager);

}
