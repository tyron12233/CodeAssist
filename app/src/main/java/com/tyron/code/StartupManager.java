package com.tyron.code;

import java.util.ArrayDeque;
import java.util.Queue;

public class StartupManager {

    private ArrayDeque<Runnable> mStartupActivities;

    public StartupManager() {
        mStartupActivities = new ArrayDeque<>();
    }

    public void addStartupActivity(Runnable runnable) {
        mStartupActivities.add(runnable);
    }

    public void startup() {
        Runnable runnable = mStartupActivities.remove();
        while (runnable != null) {
            runnable.run();
            if (!mStartupActivities.isEmpty()) {
                runnable = mStartupActivities.remove();
            } else {
                runnable = null;
            }
        }
    }
}
