package com.tyron.code.util;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.widget.PopupMenuCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class CoordinatePopupMenu extends PopupMenu {

    private static final Field sMenuPopupField;

    static {
        try {
            sMenuPopupField = PopupMenu.class.getDeclaredField("mPopup");
            sMenuPopupField.setAccessible(true);
        } catch (Throwable e) {
            throw new Error(e);
        }
    }

    public CoordinatePopupMenu(@NonNull Context context, @NonNull View anchor) {
        super(context, anchor);
    }

    public CoordinatePopupMenu(@NonNull Context context, @NonNull View anchor, int gravity) {
        super(context, anchor, gravity);
    }

    public CoordinatePopupMenu(@NonNull Context context,
                               @NonNull View anchor,
                               int gravity,
                               int popupStyleAttr,
                               int popupStyleRes) {
        super(context, anchor, gravity, popupStyleAttr, popupStyleRes);
    }

    @Override
    public void show() {
        super.show();
    }

    /**
     * Does nothing.
     * This is to prevent unknown callers from dismissing the popup menu, use
     * {@link #dismissPopup()} instead
     */
    @Override
    public void dismiss() {
        // do nothing
    }

    public void dismissPopup() {
        super.dismiss();
    }

    public void show(int x, int y) {
        try {
            Object popup = sMenuPopupField.get(this);
            assert popup != null;

            Method show = popup.getClass()
                    .getDeclaredMethod("show", int.class, int.class);
            show.invoke(popup, x, y);
        } catch (Throwable e) {
            // should not happen, fallback to show() just in case
            show();
        }
    }
}
