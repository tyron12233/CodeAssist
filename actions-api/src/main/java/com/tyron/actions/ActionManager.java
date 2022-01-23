package com.tyron.actions;

import android.view.Menu;

import androidx.annotation.NonNull;

import com.tyron.actions.impl.ActionManagerImpl;

public abstract class ActionManager {

    private static ActionManager sInstance = null;
    public static ActionManager getInstance() {
        if (sInstance == null) {
            sInstance = new ActionManagerImpl();
        }
        return sInstance;
    }

    public abstract void fillMenu(DataContext context, Menu menu, String place, boolean isContext, boolean isToolbar);

    public abstract String getId(@NonNull AnAction action);

    public abstract void registerAction(@NonNull String actionId, @NonNull AnAction action);

    public abstract void unregisterAction(@NonNull String actionId);

    public abstract void replaceAction(@NonNull String actionId, @NonNull AnAction newAction);

    public abstract boolean isGroup(@NonNull String actionId);
}
