package com.tyron.completion.java.action.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.openjdk.source.util.TreePath;

/**
 * Class for providing actions in the editor given the current path or the current diagnostic code.
 *
 * Subclasses must NOT store calculated objects in its fields, this will cause a memory leak
 * as ActionProviders are stored throughout the lifetime of the app.
 *
 * Actions are called lazily when the user has selected it.
 */
public abstract class ActionProvider {

    /**
     *
     * @param context
     * @param errorCode Error code given by javac.
     * @return whether this ActionProvider is applicable to a specific error code.
     */
    public boolean isApplicable(ActionContext context, @Nullable String errorCode) {
        return false;
    }

    /**
     *
     * @param context
     * @param currentPath The TreePath representation of where the current cursor is
     * @return whether this ActionProvider is applicable to a specific tree path.
     */
    public boolean isApplicable(ActionContext context, @NonNull TreePath currentPath) {
        return false;
    }

    public abstract void addMenus(@NonNull ActionContext context);
}
