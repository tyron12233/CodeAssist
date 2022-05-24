package com.tyron.completion.progress;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MockHandler implements HandlerInterface {

    private final Timer timer = new Timer();

    @Override
    public void post(Runnable runnable) {
        runnable.run();
    }

    @Override
    public void postDelayed(Runnable runnable, long delay) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                runnable.run();
            }
        }, delay);
    }

    @Override
    public void removeCallbacks(Runnable runnable) {
        timer.cancel();
    }
}
