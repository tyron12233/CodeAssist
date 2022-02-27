package com.tyron.layoutpreview2;

import android.view.View;

import androidx.annotation.NonNull;

import org.eclipse.lemminx.dom.DOMAttr;

import java.util.List;

/**
 * Manages and updates its View. each View has its own ViewManager for updating its attributes.
 */
public interface ViewManager {

    /**
     * Return the view that this manager is using.
     *
     * @return The view instance
     */
    @NonNull
    View getView();

    /**
     * Updates the attributes and applies it to the view
     * @param attrs the attributes
     */
    void updateAttributes(@NonNull List<DOMAttr> attrs);
}
