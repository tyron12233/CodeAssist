package com.tyron.actions.impl;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.ActionGroup;
import com.tyron.actions.ActionManager;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.Presentation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActionManagerImpl extends ActionManager {

    private final Map<String, AnAction> mIdToAction = new LinkedHashMap<>();
    private final Map<Object, String> mActionToId = new HashMap<>();

    @Override
    public void fillMenu(DataContext context,
                         Menu menu,
                         String place,
                         boolean isContext,
                         boolean isToolbar) {
        // Inject values
        context.putData(CommonDataKeys.CONTEXT, context);
        if (Build.VERSION_CODES.P <= Build.VERSION.SDK_INT) {
            menu.setGroupDividerEnabled(true);
        }

        for (AnAction value : mIdToAction.values()) {

            AnActionEvent event =
                    new AnActionEvent(context, place, value.getTemplatePresentation(), isContext,
                                      isToolbar);

            event.setPresentation(value.getTemplatePresentation());

            value.update(event);

            if (event.getPresentation()
                    .isVisible()) {
                fillMenu(menu, value, event);
            }
        }
    }


    private void fillMenu(Menu menu, AnAction action, AnActionEvent event) {
        Presentation presentation = event.getPresentation();

        MenuItem menuItem;
        if (isGroup(action)) {
            ActionGroup actionGroup = (ActionGroup) action;
            if (!actionGroup.isPopup()) {
                fillMenu(View.generateViewId(), menu, actionGroup, event);
                return;
            }
            SubMenu subMenu = menu.addSubMenu(presentation.getText());
            menuItem = subMenu.getItem();

            AnAction[] children = actionGroup.getChildren(event);
            if (children != null) {
                for (AnAction child : children) {
                    event.setPresentation(child.getTemplatePresentation());

                    child.update(event);

                    if (event.getPresentation()
                            .isVisible()) {
                        if (actionGroup.isPopup()) {
                            fillSubMenu(subMenu, child, event);
                        }
                    }
                }
            }
        } else {
            menuItem = menu.add(presentation.getText());
        }

        menuItem.setEnabled(presentation.isEnabled());
        menuItem.setTitle(presentation.getText());
        if (presentation.getIcon() != null) {
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        } else {
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        menuItem.setIcon(presentation.getIcon());
        menuItem.setOnMenuItemClickListener(item -> performAction(action, event));
    }

    private void fillMenu(int id, Menu menu, ActionGroup group, AnActionEvent event) {
        AnAction[] children = group.getChildren(event);
        if (children == null) {
            return;
        }

        for (AnAction child : children) {
            event.setPresentation(child.getTemplatePresentation());
            child.update(event);
            if (event.getPresentation()
                    .isVisible()) {
                MenuItem add = menu.add(id, Menu.NONE, Menu.NONE, event.getPresentation()
                        .getText());
                add.setEnabled(event.getPresentation()
                                       .isEnabled());
                add.setIcon(event.getPresentation()
                                    .getIcon());
                add.setOnMenuItemClickListener(item -> performAction(child, event));
            }
        }
    }

    private void fillSubMenu(SubMenu subMenu, AnAction action, AnActionEvent event) {
        Presentation presentation = event.getPresentation();

        MenuItem menuItem;

        if (isGroup(action)) {
            ActionGroup group = (ActionGroup) action;
            if (!group.isPopup()) {
                fillMenu(View.generateViewId(), subMenu, group, event);
            }

            SubMenu subSubMenu = subMenu.addSubMenu(presentation.getText());
            menuItem = subSubMenu.getItem();

            AnAction[] children = group.getChildren(event);
            if (children != null) {
                for (AnAction child : children) {
                    event.setPresentation(child.getTemplatePresentation());

                    child.update(event);

                    if (event.getPresentation()
                            .isVisible()) {
                        fillSubMenu(subSubMenu, child, event);
                    }
                }
            }
        } else {
            menuItem = subMenu.add(presentation.getText());
        }

        menuItem.setEnabled(presentation.isEnabled());
        if (presentation.getIcon() != null) {
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        } else {
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        menuItem.setIcon(presentation.getIcon());
        menuItem.setContentDescription(presentation.getDescription());
        menuItem.setOnMenuItemClickListener(item -> performAction(action, event));
    }

    private boolean performAction(AnAction action, AnActionEvent event) {
        try {
            action.actionPerformed(event);
        } catch (Throwable e) {
            ClipboardManager clipboardManager = event.getDataContext()
                    .getSystemService(ClipboardManager.class);
            new MaterialAlertDialogBuilder(event.getDataContext()).setTitle(
                    "Unable to perform action")
                    .setMessage(e.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.copy,
                                       (d, w) -> clipboardManager.setPrimaryClip(
                                               ClipData.newPlainText("Error report",
                                                                     Log.getStackTraceString(e))))
                    .show();
            return false;
        }
        return true;
    }

    @Override
    public String getId(@NonNull AnAction action) {
        return mActionToId.get(action);
    }

    @Override
    public void registerAction(@NonNull String actionId, @NonNull AnAction action) {
        mIdToAction.put(actionId, action);
        mActionToId.put(action, actionId);
    }

    @Override
    public void unregisterAction(@NonNull String actionId) {
        AnAction anAction = mIdToAction.get(actionId);
        if (anAction != null) {
            mIdToAction.remove(actionId);
            mActionToId.remove(anAction);
        }
    }

    @Override
    public void replaceAction(@NonNull String actionId, @NonNull AnAction newAction) {
        unregisterAction(actionId);
        registerAction(actionId, newAction);
    }

    @Override
    public boolean isGroup(@NonNull String actionId) {
        return isGroup(mIdToAction.get(actionId));
    }

    private boolean isGroup(AnAction action) {
        return action instanceof ActionGroup;
    }
}
