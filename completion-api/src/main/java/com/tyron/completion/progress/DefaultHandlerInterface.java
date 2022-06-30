package com.tyron.completion.progress;

import android.os.Handler;

public class DefaultHandlerInterface implements HandlerInterface {

    private final Handler handler;

    public DefaultHandlerInterface(Handler handler) {
        this.handler = handler;
    }

    public Handler getHandler() {
        return handler;
    }


    @Override
    public void post(Runnable runnable) {
        handler.post(runnable);
    }

    @Override
    public void postDelayed(Runnable runnable, long delay) {
        handler.postDelayed(runnable, delay);
    }

    @Override
    public void removeCallbacks(Runnable runnable) {
        handler.removeCallbacks(runnable);
    }
}
