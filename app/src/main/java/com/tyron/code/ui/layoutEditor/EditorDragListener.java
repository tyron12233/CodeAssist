package com.tyron.code.ui.layoutEditor;

import android.util.Log;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.tyron.code.ui.layoutEditor.model.EditorShadowView;

public class EditorDragListener implements View.OnDragListener {

    private final EditorShadowView mEditorShadow;

    public EditorDragListener(View root) {
        mEditorShadow = new EditorShadowView(root.getContext());
        mEditorShadow.setLayoutParams(new ViewGroup.LayoutParams(50, 50));
        mEditorShadow.setBackgroundColor(0xff000000);
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
            case DragEvent.ACTION_DROP:
                ensureNoParent(mEditorShadow);
                break;
        }
        return true;
    }

    private void addView(ViewGroup parent, View child, DragEvent event) {
        int index = parent.getChildCount();
        if (parent instanceof LinearLayout) {
            if (((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                index = getVerticalIndexForEvent(parent, event);
            } else {
                index = getHorizontalIndexForEvent(parent, event);
            }
        }
        parent.addView(child, index);
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

            if (child.getRight() < dropX) {
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

            if (child.getTop() < dropY) {
                index++;
            }
        }
        return index;
    }
}
