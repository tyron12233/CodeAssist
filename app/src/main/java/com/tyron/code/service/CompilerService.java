package com.tyron.code.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tyron.code.R;
import com.tyron.code.compiler.ApkBuilder;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.util.ApkInstaller;

import java.io.File;

public class CompilerService extends Service {

    private final CompilerBinder mBinder = new CompilerBinder();
    public class CompilerBinder extends Binder {
        public CompilerService getCompilerService() {
            return CompilerService.this;
        }
    }

    private Project mProject;
    private ApkBuilder.OnResultListener onResultListener;
    private ILogger external;
    private final ILogger logger = new ILogger() {
        @Override
        public void error(String message) {
            if (external != null) {
                external.error(message);
            }
        }

        @Override
        public void warning(String message) {
            if (external != null) {
                external.warning(message);
            }
        }

        @Override
        public void debug(String message) {
            if (external != null) {
                external.debug(message);
            }
        }
    };

    public void setLogger(ILogger logger) {
        this.external = logger;
    }

    public void setOnResultListener(ApkBuilder.OnResultListener listener) {
        onResultListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mProject = FileManager.getInstance().getCurrentProject();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Notification notification = setupNotification();
        startForeground(201, notification);

        Log.d("CompilerService", "Started compiler service");
        compile();

        return START_STICKY;
    }

    private Notification setupNotification() {

        String channelId = createNotificationChannel();

        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("CodeAssist")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentText("Compiling " + mProject.mRoot.getName())
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
            .build();
    }

    private void updateNotification(String title, String message) {

        Notification notif = new NotificationCompat.Builder(this, "Compiler")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(Notification.PRIORITY_MIN)
                .build();

        NotificationManagerCompat.from(this)
                .notify(201, notif);
    }

    private String createNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder("Compiler",
                NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("Compiler service")
                .setDescription("Foreground notification for the compiler")
                .build();

        NotificationManagerCompat.from(this)
                .createNotificationChannel(channel);

        return "Compiler";
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void compile() {
        ApkBuilder apkBuilder = new ApkBuilder(logger, mProject);
        apkBuilder.setTaskListener(this::updateNotification);
        apkBuilder.build((success, message) -> {
            if (onResultListener != null) {
                onResultListener.onComplete(success, message);
            }

            String projectName = mProject.mRoot.getName();
            stopForeground(true);

            if (!success) {
                updateNotification(projectName, "Compilation failed");
            } else {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(ApkInstaller.uriFromFile(this, new File(mProject.getBuildDirectory(), "bin/signed.apk")), "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                PendingIntent pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

                Notification notification = new NotificationCompat.Builder(this, "Compiler")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle(projectName)
                        .setContentText("Compilation success")
                        .addAction(new NotificationCompat.Action(0, "INSTALL", pending))
                        .build();
                NotificationManagerCompat.from(this)
                        .notify(201, notification);
            }

        });
    }
}
