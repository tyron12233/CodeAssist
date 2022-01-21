package com.tyron.completion.xml;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tyron.actions.ActionManager;
import com.tyron.completion.xml.action.context.AndroidManifestAddPermissionsAction;

public class XmlCompletionModule {

    private static Context sApplicationContext;
    private static final Handler sApplicationHandler = new Handler(Looper.getMainLooper());

    public static void registerActions(ActionManager actionManager) {
        actionManager.registerAction(AndroidManifestAddPermissionsAction.ID, new AndroidManifestAddPermissionsAction());
    }

    public static void initialize(Context context) {
        sApplicationContext = context.getApplicationContext();
    }

    public static Context getContext() {
        return sApplicationContext;
    }

    public static void post(Runnable runnable) {
        sApplicationHandler.post(runnable);
    }

}
