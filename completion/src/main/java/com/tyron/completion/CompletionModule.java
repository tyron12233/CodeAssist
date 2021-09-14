package com.tyron.completion;

import android.content.Context;

public class CompletionModule {

    private static Context mApplicationContext;

    public static void initialize(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    public static Context getContext() {
        return mApplicationContext;
    }
}
