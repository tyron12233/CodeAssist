package com.tyron.code;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.developer.crashx.config.CrashConfig;
import com.tyron.builder.BuildModule;
import com.tyron.code.event.EventManager;
import com.tyron.code.service.GradleDaemonService;
import com.tyron.code.ui.settings.ApplicationSettingsFragment;
import com.tyron.common.ApplicationProvider;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.xml.XmlCompletionModule;

import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.jetbrains.kotlin.cli.common.environment.UtilKt;
import org.jetbrains.kotlin.cli.jvm.compiler.IdeaStandaloneExecutionSetup;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreApplicationEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.utils.PrintingLogger;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.IOException;

public class ApplicationLoader extends Application {

    private static ApplicationLoader sInstance;
    public static Context applicationContext;

    public static ApplicationLoader getInstance() {
        return sInstance;
    }

    private EventManager mEventManager;
    private JavaCoreApplicationEnvironment coreApplicationEnvironment;

    private final Disposable disposable = Disposer.newDisposable("Application Environment");

    @Override
    public void onTerminate() {
        super.onTerminate();
    }

    @Override
    public void onCreate() {
        Logger.setFactory(category -> new PrintingLogger(System.out));

        Timer timer = Time.startTimer();

        UtilKt.setIdeaIoUseFallback();
        IdeaStandaloneExecutionSetup.INSTANCE.doSetup();

        File intellijHome = new File(getFilesDir(), "intellij_home");
        if (!intellijHome.exists()) {
            FileUtil.createDirectory(intellijHome);
        }
        System.setProperty("idea.home.path", intellijHome.getAbsolutePath());
        System.setProperty("trace.file.based.index.update", "true");
        System.setProperty("trace.stub.index.update", "true");

        coreApplicationEnvironment = new CodeAssistApplicationEnvironment(disposable, false);


        super.onCreate();
        System.out.println("onCreate took " + timer.getElapsed());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Lsun/misc/Unsafe");
        }

        setupTheme();

        mEventManager = new EventManager();

        sInstance = this;
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

        File userDir = new File(getFilesDir(), "user_dir");
        System.setProperty("codeassist.user.dir", userDir.getAbsolutePath());
    }

    public JavaCoreApplicationEnvironment getCoreApplicationEnvironment() {
        return coreApplicationEnvironment;
    }

    /**
     * Can be used to communicate within the application globally
     *
     * @return the EventManager
     */
    @NonNull
    public EventManager getEventManager() {
        return mEventManager;
    }

    private void setupTheme() {
        ApplicationSettingsFragment.ThemeProvider provider =
                new ApplicationSettingsFragment.ThemeProvider(this);
        int theme = provider.getThemeFromPreferences();
        AppCompatDelegate.setDefaultNightMode(theme);
    }



    public static SharedPreferences getDefaultPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(applicationContext);
    }

    public static void showToast(String message) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show();
    }

    @VisibleForTesting
    public static void setApplicationContext(Context context) {
        applicationContext = context;
    }

    /**
     * Accessed reflectively via {@link org.gradle.launcher.daemon.client.DefaultDaemonStarter}
     */
    @Keep
    private static void startDaemonProcess(File dir) throws IOException {
        assert applicationContext != null;


        Intent intent = new Intent(applicationContext, GradleDaemonService.class);
        intent.putExtra("dir", dir.toString());

        applicationContext.startService(intent);
    }
}