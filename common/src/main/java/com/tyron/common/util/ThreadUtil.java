package com.tyron.common.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadUtil {

    private static final ExecutorService sExecutorService = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static void runOnBackgroundThread(Runnable runnable) {
        sExecutorService.execute(runnable);
    }

    public static void runOnUiThread(Runnable runnable) {
        sMainHandler.post(runnable);
    }
}
