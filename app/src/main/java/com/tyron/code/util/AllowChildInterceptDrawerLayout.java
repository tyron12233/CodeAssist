package com.tyron.code.util;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Allows horizontally scrolling child of drawer layouts to intercept the touch event.
 */
public class AllowChildInterceptDrawerLayout extends DrawerLayout {

    private final Rect rect = new Rect();

    public AllowChildInterceptDrawerLayout(@NonNull Context context) {
        super(context);
    }

    public AllowChildInterceptDrawerLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AllowChildInterceptDrawerLayout(@NonNull Context context,
                                           @Nullable AttributeSet attrs,
                                           int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        View scrollingChild = findScrollingChild(this, ev.getX(), ev.getY());
        if (scrollingChild != null) {
            return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    /**
     * Recursively finds the view that can scroll horizontally to the end
     * @param parent The starting parent to search
     * @param x The x point in the screen
     * @param y The y point in the screen
     * @return The scrolling view, null if no view is found
     */
    private View findScrollingChild(ViewGroup parent, float x, float y) {
        int n = parent.getChildCount();
        if (parent == this && n <= 1) {
            return null;
        }

        int start = 0;
        if (parent == this) {
            start = 1;
        }

        for (int i = start; i < n; i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            child.getHitRect(rect);
            if (rect.contains((int) x, (int) y)) {
                if (child.canScrollHorizontally(1)) {
                    return child;
                } else if (child instanceof ViewGroup) {
                    View v = findScrollingChild((ViewGroup) child, x - rect.left, y - rect.top);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

}
