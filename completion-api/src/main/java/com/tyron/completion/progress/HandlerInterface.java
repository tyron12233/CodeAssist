package com.tyron.completion.progress;

public interface HandlerInterface {

    void post(Runnable runnable);

    void postDelayed(Runnable runnable, long delay);

    void removeCallbacks(Runnable runnable);
}
