package com.tyron.code.ui.layoutEditor.model;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

/**
 * Information passed in an editor drag
 */
public class EditorDragState {

    public static EditorDragState fromView(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        return new EditorDragState(view, parent);
    }

    public static EditorDragState fromPalette(ViewPalette palette) {
        return new EditorDragState(palette);
    }

    private final View mView;
    private final ViewGroup mParent;
    private final int mIndex;
    private final boolean mIsExistingView;
    private final ViewPalette mPalette;

    private EditorDragState(View view, ViewGroup parent) {
        mView = view;
        mParent = parent;
        mIndex = parent.indexOfChild(view);
        mIsExistingView = true;
        mPalette = null;
    }

    private EditorDragState(ViewPalette palette) {
        mView = null;
        mParent = null;
        mIsExistingView = false;
        mIndex = -1;
        mPalette = palette;
    }

    /**
     * @return The index of this child to its parent prior to being removed
     */
    public int getIndex() {
        return mIndex;
    }

    /**
     * @return The parent of this view before the drag has been initiated.
     * Returns null if {@link #isExistingView()} returned false.
     */
    public ViewGroup getParent() {
        if (!isExistingView()) {
            return null;
        }
        return mParent;
    }

    /**
     * @return whether the view that is currently being dragged is already in the editor before
     * e.g. when an exiting view is dragged to another view.
     */
    public boolean isExistingView() {
        return mIsExistingView;
    }

    /**
     * @return The view palette associated with the drag event, returns null
     * if {@link #isExistingView()} returned true.
     */
    public ViewPalette getPalette() {
        return mPalette;
    }

    /**
     * @return the current view that is being dragged, may return null if
     * {@link #isExistingView()} returned false.
     */
    @Nullable
    public View getView() {
        if (!isExistingView()) {
            return null;
        }
        return mView;
    }
}
