package com.tyron.builder.compiler.log;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Logger {

    private static final String DEBUG = "DEBUG";
    private static final String WARNING = "WARNING";
    private static final String ERROR = "ERROR";
    private static final String INFO = "INFO";
    private static final Pattern TYPE_PATTERN = Pattern.compile("^(.*\\d) ([ADEIW]) (.*): (.*)");

    private static volatile boolean mInitialized;
    private static Context mContext;

    public static void initialize(Context context) {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mContext = context.getApplicationContext();

        start();
    }

    private static void start() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                clear();
                Process process = Runtime.getRuntime()
                        .exec("logcat");
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream()));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = TYPE_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String type = matcher.group(2);
                        if (type != null) {
                           switch (type) {
                               case "D": debug(line);   break;
                               case "E": error(line);   break;
                               case "W": warning(line); break;
                               case "I": info(line);    break;
                           }
                        } else {
                            debug(line);
                        }
                    }
                }
            } catch (IOException e) {
                error("IOException occurred on Logger: " + e.getMessage());
            }
        });
    }

    private static void clear() throws IOException {
        Runtime.getRuntime().exec("logcat -c");
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

    private static void info(String message) {
        broadcast(INFO, message);
    }

    private static void broadcast(String type, String message) {
        Intent intent = new Intent(mContext.getPackageName() + ".LOG");
        intent.putExtra("type", type);
        intent.putExtra("message", message);
        mContext.sendBroadcast(intent);
    }
}
