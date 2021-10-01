package com.tyron.code;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.cardview.widget.CardView;

import com.developer.crashx.config.CrashConfig;
import com.tyron.builder.BuildModule;
import com.tyron.completion.CompletionModule;

public class ApplicationLoader extends Application {
    
    public static Context applicationContext;
    public static Handler applicationHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        applicationContext = this;
        CompletionModule.initialize(applicationContext);
        BuildModule.initialize(applicationContext);
        CrashConfig.Builder.create()
                .backgroundMode(CrashConfig.BACKGROUND_MODE_SHOW_CUSTOM)
                .enabled(true)
                .showErrorDetails(true)
                .showRestartButton(true)
                .logErrorOnRestart(true)
                .trackActivities(true)
                .apply();
    }
    
    public static void showToast(String message) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
                .show();
    }
}
