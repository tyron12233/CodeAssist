package com.tyron.common.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.tyron.common.ApplicationProvider;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ApkInstaller {

    private static final String TAG = ApkInstaller.class.getSimpleName();

    private static Method FILE_PROVIDER_METHOD;

    static {
        try {
            Class<?> aClass = Class.forName("androidx.core.content.FileProvider");
            FILE_PROVIDER_METHOD = aClass.getDeclaredMethod("getUriForFile",
                    Context.class,
                    String.class,
                    File.class);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void installApplication(String applicationId, String filePath) {
        installApplication(ApplicationProvider.getApplicationContext(), applicationId, filePath);
    }

    public static void installApplication(Context context, String applicationId, String filePath) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uriFromFile(context, applicationId, new File(filePath)), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
            Log.e(TAG, "Error in opening the file!");
        }
    }

    public static Uri uriFromFile(Context context, String applicationId, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return (Uri) FILE_PROVIDER_METHOD.invoke(null, context, applicationId + ".provider", file);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        } else {
            return Uri.fromFile(file);
        }
    }

}