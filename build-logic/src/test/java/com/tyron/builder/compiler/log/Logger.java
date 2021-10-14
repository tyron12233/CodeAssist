package com.tyron.builder.compiler.log;

import android.content.Context;
import android.content.Intent;

public class Logger {

    private static final String DEBUG = "DEBUG";
    private static final String WARNING = "WARNING";
    private static final String ERROR = "ERROR";

    private static volatile boolean mInitialized;
    private static Context mContext;

    public static void initialize(Context context) {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mContext = context.getApplicationContext();

        debug("HELLO I AM STARTED");
    }

    private static void debug(String message) {
        broadcast(DEBUG, message);
    }

    private static void warning(String message) {
        broadcast(WARNING, message);
    }

    private static void error(String message) {
        broadcast(ERROR, message);
    }

    private static void broadcast(String type, String message) {
        Intent intent = new Intent(mContext.getPackageName() + ".LOG");
        intent.putExtra("type", type);
        intent.putExtra("message", message);
        mContext.sendBroadcast(intent);
    }
}
