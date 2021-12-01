package com.tyron.completion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tyron.builder.BuildModule;
import com.tyron.common.util.Decompress;

import java.io.File;

public class CompletionModule {

    private static Context sApplicationContext;
    private static final Handler sApplicationHandler = new Handler(Looper.getMainLooper());
    private static File sAndroidJar;
    private static File sLambdaStubs;

    public static void initialize(Context context) {
        sApplicationContext = context.getApplicationContext();
    }

    public static Context getContext() {
        return sApplicationContext;
    }

    public static void post(Runnable runnable) {
        sApplicationHandler.post(runnable);
    }

    public static File getAndroidJar() {
        if (sAndroidJar == null) {
            Context context = BuildModule.getContext();
            if (context == null) {
                return null;
            }

            sAndroidJar = new File(context
                    .getFilesDir(), "rt.jar");
            if (!sAndroidJar.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(),
                        "rt.zip",
                        sAndroidJar.getParentFile().getAbsolutePath());
            }
        }

        return sAndroidJar;
    }

    public static File getLambdaStubs() {
        if (sLambdaStubs == null) {
            sLambdaStubs = new File(BuildModule.getContext().getFilesDir(), "core-lambda-stubs.jar");

            if (!sLambdaStubs.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(), "lambda-stubs.zip", sLambdaStubs.getParentFile().getAbsolutePath());
            }
        }
        return sLambdaStubs;
    }
}
