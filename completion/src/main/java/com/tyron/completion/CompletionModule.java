package com.tyron.completion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class CompletionModule {

    private static Context mApplicationContext;
    private static final Handler mApplicationHandler = new Handler(Looper.getMainLooper());

    public static void initialize(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    public static Context getContext() {
        return mApplicationContext;
    }

    public static void post(Runnable runnable) {
        mApplicationHandler.post(runnable);
    }
}
