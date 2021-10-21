package com.tyron.code.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tyron.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.code.R;

public class IndexService extends Service {

    private static final int NOTIFICATION_ID = 23;

    private final IndexBinder mBinder = new IndexBinder();

    public IndexService() {
    }

    public class IndexBinder extends Binder {
        public void index(Project project, ProjectManager.TaskListener listener, ILogger logger) {
            IndexService.this.index(project, listener, logger);
        }
    }


    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, createNotificationChannel())
                .setProgress(100, 0, true)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Indexing")
                .setContentText("Preparing")
                .build();
        updateNotification(notification);
        startForeground(NOTIFICATION_ID, notification);

        return super.onStartCommand(intent, flags, startId);
    }

    private void index(Project project, ProjectManager.TaskListener listener, ILogger logger) {

        ProjectManager.TaskListener delegate = new ProjectManager.TaskListener() {
            @Override
            public void onTaskStarted(String message) {
                Notification notification = new NotificationCompat.Builder(IndexService.this, "Index")
                        .setProgress(100, 0, true)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Indexing")
                        .setContentText(message)
                        .build();
                updateNotification(notification);
                listener.onTaskStarted(message);
            }

            @Override
            public void onComplete(boolean success, String message) {
                listener.onComplete(success, message);
                stopForeground(true);
            }
        };
        ProjectManager.getInstance()
                .openProject(project, true, delegate, logger);
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