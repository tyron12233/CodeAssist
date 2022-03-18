package com.tyron.code.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.code.R;

import java.lang.ref.WeakReference;

public class IndexService extends Service {

    private static final int NOTIFICATION_ID = 23;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final IndexBinder mBinder = new IndexBinder(this);

    public IndexService() {
    }

    public static class IndexBinder extends Binder {

        public final WeakReference<IndexService> mIndexServiceReference;

        public IndexBinder(IndexService service) {
            mIndexServiceReference = new WeakReference<>(service);
        }

        public void index(Project project, ProjectManager.TaskListener listener, ILogger logger) {
            IndexService service = mIndexServiceReference.get();
            if (service == null) {
                listener.onComplete(project, false, "Index service is null!");
            } else {
                service.index(project, listener, logger);
            }
        }
    }


    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, createNotificationChannel())
                .setProgress(100, 0, true)
                .setSmallIcon(R.drawable.ic_stat_code)
                .setContentTitle("Indexing")
                .setContentText("Preparing")
                .build();
        updateNotification(notification);
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void index(Project project, ProjectManager.TaskListener listener, ILogger logger) {
        ProjectManager.TaskListener delegate = new ProjectManager.TaskListener() {
            @Override
            public void onTaskStarted(String message) {
                Notification notification = new NotificationCompat.Builder(IndexService.this, "Index")
                        .setProgress(100, 0, true)
                        .setSmallIcon(R.drawable.ic_stat_code)
                        .setContentTitle("Indexing")
                        .setContentText(message)
                        .build();
                updateNotification(notification);
                mMainHandler.post(() -> listener.onTaskStarted(message));
            }

            @Override
            public void onComplete(Project project, boolean success, String message) {
                mMainHandler.post(() -> listener.onComplete(project, success, message));
                stopForeground(true);
                stopSelf();
            }
        };

        try {
            ProjectManager.getInstance()
                    .openProject(project, true, delegate, logger);
        } catch (Throwable e) {
            stopForeground(true);
            Notification notification = new NotificationCompat.Builder(IndexService.this, "Index")
                    .setProgress(100, 0, true)
                    .setSmallIcon(R.drawable.ic_stat_code)
                    .setContentTitle("Indexing error")
                    .setContentText("Unknown error: " + e.getMessage())
                    .build();
            updateNotification(notification);
            stopSelf();
            throw e;
        }
    }

    private String createNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder("Index",
                NotificationManagerCompat.IMPORTANCE_NONE)
                .setName("Index Service")
                .setDescription("Service that downloads libraries in the foreground")
                .build();
        NotificationManagerCompat.from(this)
                .createNotificationChannel(channel);
        return "Index";
    }

    private void updateNotification(Notification notification) {
        NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, notification);
    }
}