package com.tyron.code.ui.layoutEditor;

import android.animation.LayoutTransition;
import android.graphics.Rect;
import android.util.Log;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;
import com.tyron.code.BuildConfig;
import com.tyron.code.ui.layoutEditor.model.EditorDragState;
import com.tyron.code.ui.layoutEditor.model.EditorShadowView;
import com.tyron.code.ui.layoutEditor.model.ViewPalette;
import com.tyron.layoutpreview.BoundaryDrawingFrameLayout;

import java.util.Objects;

public class EditorDragListener implements View.OnDragListener {

    private static final String TAG = EditorDragListener.class.getSimpleName();

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

        if (!(event.getLocalState() instanceof EditorDragState)) {
            return false;
        }

        EditorDragState dragState = (EditorDragState) event.getLocalState();

        ViewGroup hostView = (ViewGroup) view;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION:
            case DragEvent.ACTION_DRAG_ENTERED:
                ensureNoParent(mEditorShadow);
                if (event.getLocalState() instanceof ProteusView) {
                    ensureNoParent(((ProteusView) event.getLocalState()).getAsView());
                }
                addView(hostView, mEditorShadow, event);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                ensureNoParent(mEditorShadow);
                break;
            case DragEvent.ACTION_DROP:
                ensureNoParent(mEditorShadow);

                if (dragState.isExistingView()) {

                    if (getParentOfType(hostView, BoundaryDrawingFrameLayout.class) == null) {
                        ensureNoParent(Objects.requireNonNull(dragState.getView()));
                        return true;
                    }

                    if (dragState.getView() instanceof ProteusView) {
                        boolean added = addProteusView(hostView, (ProteusView) dragState.getView(), event);

                        // the view cannot be added, add it back to its previous parent
                        if (!added) {
                            ensureNoParent(dragState.getView());
                            dragState.getParent().addView(dragState.getView(), dragState.getIndex());

                            // inform the main editor
                            if (mDelegate != null) {
                                mDelegate.onAddView(dragState.getParent(), dragState.getView());
                            }
                        }
                    }
                } else {
                    addPalette(hostView, dragState.getPalette(), event);
                }
                break;
        }
        return true;
    }

    private boolean addProteusView(ViewGroup parent, ProteusView view, DragEvent event) {
        return addView(parent, view.getAsView(), event);
    }

    private void addPalette(ViewGroup parent, ViewPalette palette, DragEvent event) {
        if (mInflateCallback != null) {
            ProteusView inflated = mInflateCallback.inflate(parent, palette);
            addView(parent, inflated.getAsView(), event);
        }
    }

    private boolean addView(ViewGroup parent, View child, DragEvent event) {
        try {
            ensureNoParent(child);
            int index = parent.getChildCount();
            if (parent instanceof LinearLayout) {
                if (((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                    index = getVerticalIndexForEvent(parent, event);
                } else {
                    index = getHorizontalIndexForEvent(parent, event);
                }
            }

            if (mEditorShadow.equals(child)) {
                LayoutTransition transition = parent.getLayoutTransition();
                parent.setLayoutTransition(null);
                parent.addView(child, index);
                parent.setLayoutTransition(transition);
            } else {
                parent.addView(child, index);
                if (mDelegate != null) {
                    mDelegate.onAddView(parent, child);
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void ensureNoParent(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            if (view.equals(mEditorShadow)) {
                LayoutTransition transition = parent.getLayoutTransition();
                parent.setLayoutTransition(null);
                parent.removeView(view);
                parent.setLayoutTransition(transition);
            } else {
                parent.removeView(view);
                if (mDelegate != null) {
                    mDelegate.onRemoveView(parent, view);
                }
            }
        }
    }

    private int getHorizontalIndexForEvent(ViewGroup parent, DragEvent event) {
        float dropX = event.getX();
        int index = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);

            if (mEditorShadow.equals(child)) {
                // do not count the shadow as a child view
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

    private View getParentOfType(View view, Class<? extends View> type) {
        ViewParent current = view.getParent();
        while (current != null) {
            if (current.getClass().isAssignableFrom(type)) {
                return (View) current;
            }

            current = current.getParent();
        }

        return null;
    }
}
