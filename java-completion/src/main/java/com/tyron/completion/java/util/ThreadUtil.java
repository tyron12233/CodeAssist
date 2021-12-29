package com.tyron.completion.java.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadUtil {

    private static ExecutorService sExecutorService = Executors.newSingleThreadExecutor();

    public static void runOnBackgroundThread(Runnable runnable) {
        sExecutorService.execute(runnable);
    }
}
