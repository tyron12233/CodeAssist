package com.tyron.code;

import android.content.Context;

public class Logger {

    private static Context mContext;

    public static void initialize(Context context) {
        context = context.getApplicationContext();
    }

    
}