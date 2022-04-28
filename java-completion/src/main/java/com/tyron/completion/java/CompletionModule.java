package com.tyron.completion.java;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import androidx.annotation.VisibleForTesting;

import com.tyron.actions.ActionManager;
import com.tyron.builder.BuildModule;
import com.tyron.common.util.Decompress;
import com.tyron.completion.java.action.context.IntroduceLocalVariableAction;
import com.tyron.completion.java.action.context.OverrideInheritedMethodsAction;
import com.tyron.completion.java.action.quickfix.AddCatchClauseAction;
import com.tyron.completion.java.action.quickfix.AddThrowsAction;
import com.tyron.completion.java.action.quickfix.ImplementAbstractMethodsFix;
import com.tyron.completion.java.action.quickfix.ImportClassAction;
import com.tyron.completion.java.action.quickfix.ImportClassFieldFix;
import com.tyron.completion.java.action.quickfix.SurroundWithTryCatchAction;

import java.io.File;

public class CompletionModule {

    private static Context sApplicationContext;
    private static final Handler sApplicationHandler = new Handler(Looper.getMainLooper());
    private static File sAndroidJar;
    private static File sLambdaStubs;

    public static void registerActions(ActionManager actionManager) {
        actionManager.registerAction(AddThrowsAction.ID, new AddThrowsAction());
        actionManager.registerAction(AddCatchClauseAction.ID, new AddCatchClauseAction());
        actionManager.registerAction(SurroundWithTryCatchAction.ID, new SurroundWithTryCatchAction());
        actionManager.registerAction(ImportClassAction.ID, new ImportClassAction());
        actionManager.registerAction(ImportClassFieldFix.ID, new ImportClassFieldFix());
        actionManager.registerAction(ImplementAbstractMethodsFix.ID, new ImplementAbstractMethodsFix());

        actionManager.registerAction(IntroduceLocalVariableAction.ID, new IntroduceLocalVariableAction());
        actionManager.registerAction(OverrideInheritedMethodsAction.ID, new OverrideInheritedMethodsAction());
    }


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

    @VisibleForTesting
    public static void setLambdaStubs(File file) {
        sLambdaStubs = file;
    }

    @VisibleForTesting
    public static void setAndroidJar(File file) {
        sAndroidJar = file;
    }

    public static SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(sApplicationContext);
    }
}
