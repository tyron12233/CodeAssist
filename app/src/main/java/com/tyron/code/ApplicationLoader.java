package com.tyron.code;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import com.developer.crashx.config.CrashConfig;
import com.sun.tools.javac.file.Locations;
import com.tyron.builder.BuildModule;
import com.tyron.completion.java.CompletionModule;

public class ApplicationLoader extends Application {
    
    public static Context applicationContext;
    public static Handler applicationHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        applicationContext = this;
        CompletionModule.initialize(applicationContext);
        BuildModule.initialize(applicationContext);
        Locations.setJavaHome(getFilesDir().toPath());
        CrashConfig.Builder.create()
                .backgroundMode(CrashConfig.BACKGROUND_MODE_SHOW_CUSTOM)
                .enabled(true)
                .showErrorDetails(true)
                .showRestartButton(true)
                .logErrorOnRestart(true)
                .trackActivities(true)
                .apply();
    }

    public static SharedPreferences getDefaultPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(applicationContext);
    }

    public static void showToast(String message) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
                .show();
    }

    @VisibleForTesting
    public static void setApplicationContext(Context context) {
        applicationContext = context;
    }
}
