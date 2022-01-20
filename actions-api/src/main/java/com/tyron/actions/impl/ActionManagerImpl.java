package com.tyron.actions.impl;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tyron.actions.ActionGroup;
import com.tyron.actions.ActionManager;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.Presentation;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActionManagerImpl extends ActionManager {

    private final Map<String, AnAction> mIdToAction = new TreeMap<>();
    private final Map<Object, String> mActionToId = new HashMap<>();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void asyncFillMenu(DataContext context, Menu menu, String place, boolean isContext,
                              boolean isToolbar, Runnable callback) {
        mExecutor.submit(() -> {
            fillMenu(context, menu, place, isContext, isToolbar);

            ContextCompat.getMainExecutor(context).execute(callback);
        });
    }

    @Override
    public void fillMenu(DataContext context, Menu menu, String place, boolean isContext, boolean isToolbar) {
        // Inject values
        context.putData(CommonDataKeys.CONTEXT, context);

        for (AnAction value : mIdToAction.values()) {

            AnActionEvent event = new AnActionEvent(context,
                    place,
                    value.getTemplatePresentation(),
                    isContext,
                    isToolbar);

            event.setPresentation(value.getTemplatePresentation());

            value.update(event);

            if (event.getPresentation().isVisible()) {
                fillMenu(menu, value, event);
            }
        }
    }


    private void fillMenu(Menu menu, AnAction action, AnActionEvent event) {
        Presentation presentation = event.getPresentation();

        MenuItem menuItem;
        if (isGroup(action)) {
            SubMenu subMenu = menu.addSubMenu(presentation.getText());
            menuItem = subMenu.getItem();

            ActionGroup actionGroup = (ActionGroup) action;
            AnAction[] children = actionGroup.getChildren(event);
            if (children != null) {
                for (AnAction child : children) {
                    event.setPresentation(child.getTemplatePresentation());

                    child.update(event);

                    if (event.getPresentation().isVisible()) {
                        fillSubMenu(subMenu, child, event);
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
        menuItem.setOnMenuItemClickListener(item -> {
            action.actionPerformed(event);
            return true;
        });
    }

    private void fillSubMenu(SubMenu subMenu, AnAction action, AnActionEvent event) {
        Presentation presentation = event.getPresentation();

        MenuItem menuItem;

        if (isGroup(action)) {
            ActionGroup group = (ActionGroup) action;
            SubMenu subSubMenu = subMenu.addSubMenu(presentation.getText());
            menuItem = subSubMenu.getItem();

            AnAction[] children = group.getChildren(event);
            if (children != null) {
                for (AnAction child : children) {
                    event.setPresentation(child.getTemplatePresentation());

                    child.update(event);

                    if (event.getPresentation().isVisible()) {
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
        menuItem.setOnMenuItemClickListener(item -> {
            action.actionPerformed(event);
            return true;
        });
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
