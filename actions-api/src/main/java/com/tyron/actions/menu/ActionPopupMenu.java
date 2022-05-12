package com.tyron.actions.menu;

import android.view.View;
import android.widget.PopupMenu;

import com.tyron.actions.ActionManager;
import com.tyron.actions.DataContext;

/**
 * A helper class to show a PopupMenu inflated with actions at the given place
 */
public class ActionPopupMenu {

    /**
     * Creates and immediately shows the popup menu.
     */
    public static ActionPopupMenu createAndShow(View anchor, DataContext dataContext, String place) {
        ActionPopupMenu popupMenu = new ActionPopupMenu(anchor, dataContext, place);
        popupMenu.show();
        return popupMenu;
    }

    private final PopupMenu popupMenu;

    public ActionPopupMenu(View anchor, DataContext dataContext, String place) {
        popupMenu = new PopupMenu(dataContext, anchor);
        ActionManager.getInstance().fillMenu(dataContext, popupMenu.getMenu(), place, true, false);
    }

    public void show() {
        popupMenu.show();
    }

    public void dismiss() {
        popupMenu.dismiss();
    }
}
