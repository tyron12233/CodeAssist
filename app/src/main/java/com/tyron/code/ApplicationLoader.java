package com.tyron.code;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.developer.crashx.config.CrashConfig;
import com.tyron.builder.BuildModule;
import com.tyron.code.ui.settings.ApplicationSettingsFragment;
import com.tyron.common.ApplicationProvider;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.xml.XmlCompletionModule;

public class ApplicationLoader extends Application {
    
    public static Context applicationContext;
    public static Handler applicationHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        setupTheme();
        applicationContext = this;

        ApplicationProvider.initialize(applicationContext);

        CompletionModule.initialize(applicationContext);
        XmlCompletionModule.initialize(applicationContext);
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

    private void setupTheme() {
        ApplicationSettingsFragment.ThemeProvider provider = new ApplicationSettingsFragment.ThemeProvider(this);
        int theme = provider.getThemeFromPreferences();
        AppCompatDelegate.setDefaultNightMode(theme);
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
