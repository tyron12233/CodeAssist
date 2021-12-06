package com.tyron.code.ui.layoutEditor;

import android.util.Log;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;
import com.tyron.code.ui.layoutEditor.model.EditorShadowView;
import com.tyron.code.ui.layoutEditor.model.ViewPalette;

public class EditorDragListener implements View.OnDragListener {

    /**
     * Callbacks when views are added to the editor.
     * Shadow View is not included
     */
    public interface Delegate {
        void onAddView(ViewGroup parent, View view);
        void onRemoveView(ViewGroup parent, View view);
    }

    public interface InflateCallback {
        ProteusView inflate(ViewGroup parent, ViewPalette palette);
    }

    private final EditorShadowView mEditorShadow;
    private Delegate mDelegate;
    private InflateCallback mInflateCallback;

    public EditorDragListener(View root) {
        mEditorShadow = new EditorShadowView(root.getContext());
        mEditorShadow.setLayoutParams(new ViewGroup.LayoutParams(50, 50));
        mEditorShadow.setBackgroundColor(0xff000000);
    }

    public void setInflateCallback(@NonNull InflateCallback inflateCallback) {
        mInflateCallback = inflateCallback;
    }

    public void setDelegate(@NonNull Delegate delegate) {
        mDelegate = delegate;
    }

    @Override
    public boolean onDrag(View view, DragEvent event) {

        // Only ViewGroups should receive the drag events
        // since we can only add views on ViewGroups
        if (!(view instanceof ViewGroup)) {
            return false;
        }

        ViewGroup hostView = (ViewGroup) view;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION:
            case DragEvent.ACTION_DRAG_ENTERED:
                ensureNoParent(mEditorShadow);
                addView(hostView, mEditorShadow, event);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                break;
            case DragEvent.ACTION_DROP:
                ensureNoParent(mEditorShadow);

                Object state = event.getLocalState();

                if (state instanceof ProteusView) {
                    addProteusView(hostView, ((ProteusView) state), event);
                } else if (state instanceof ViewPalette) {
                    addPalette(hostView, ((ViewPalette) state), event);
                }
                break;
        }
        return true;
    }

    private void addProteusView(ViewGroup parent, ProteusView view, DragEvent event) {
        addView(parent, view.getAsView(), event);
    }

    private void addPalette(ViewGroup parent, ViewPalette palette, DragEvent event) {
        if (mInflateCallback != null) {
            ProteusView inflated = mInflateCallback.inflate(parent, palette);
            addView(parent, inflated.getAsView(), event);
        }
    }

    private void addView(ViewGroup parent, View child, DragEvent event) {
        ensureNoParent(child);
        int index = parent.getChildCount();
        if (parent instanceof LinearLayout) {
            if (((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                index = getVerticalIndexForEvent(parent, event);
            } else {
                index = getHorizontalIndexForEvent(parent, event);
            }
        }
        parent.addView(child, index);

        if (!mEditorShadow.equals(child)) {
            if (mDelegate != null) {
                mDelegate.onAddView(parent, child);
            }
        }
    }

    private void ensureNoParent(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
    }

    private int getHorizontalIndexForEvent(ViewGroup parent, DragEvent event) {
        float dropX = event.getX();
        int index = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);

            if (mEditorShadow.equals(child)) {
                // dont count the shadow as a child view
                continue;
            }

            if (getMiddle(child.getLeft(), child.getRight()) < dropX) {
                index++;
            }
        }
        return index;
    }

    private int getVerticalIndexForEvent(ViewGroup parent, DragEvent event) {
        float dropY = event.getY();
        int index = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);

            if (mEditorShadow.equals(child)) {
                // dont count the shadow as a child view
                continue;
            }

            if (getMiddle(child.getTop(), child.getBottom()) < dropY) {
                index++;
            }
        }
        return index;
    }

    private int getMiddle(int lower, int higher) {
        int length = higher - lower;
        int middle = length / 2;
        return lower + middle;
    }
}
