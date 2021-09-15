package com.tyron.builder;

import android.content.Context;

public class BuildModule {

    private static Context mApplicationContext;

    public static void initialize(Context applicationContext) {
            mApplicationContext = applicationContext.getApplicationContext();
    }

    public static Context getContext() {
        return mApplicationContext;
    }
}
