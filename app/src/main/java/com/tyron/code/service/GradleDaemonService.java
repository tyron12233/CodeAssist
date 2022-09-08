package com.tyron.code.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.common.base.Throwables;
import com.tyron.code.R;
import com.tyron.common.util.FileUtilsEx;

import org.apache.commons.io.FileUtils;
import org.gradle.launcher.daemon.bootstrap.DaemonMain;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * A service that runs the {@link org.gradle.launcher.daemon.bootstrap.GradleDaemon} in a
 * separate process
 */
public class GradleDaemonService extends Service {
    private static final String ACTION_STOP_DAEMON = "stopDaemon";
    private static final String EXTRA_NOTIFICATION_ID = "notificationId";

    private boolean daemonStarted = false;

    public GradleDaemonService() {

    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Notification notification = setupNotification();
        startForeground(201, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = setupNotification();
        startForeground(201, notification);

        if (ACTION_STOP_DAEMON.equals(intent.getAction())) {
            System.out.println("User has requested to stop the daemon service.");
            System.exit(0);
        }

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;

        try {
            String dir = intent.getStringExtra("dir");
            File currentDir = new File(dir);

            File daemonInput = new File(currentDir, "daemonInput");
            FileUtilsEx.createFile(daemonInput);

            File daemonOutput = new File(currentDir, "daemonOutput");
            FileUtilsEx.createFile(daemonOutput);

            System.setIn(FileUtils.openInputStream(daemonInput));
            System.setOut(new PrintStream(FileUtils.openOutputStream(daemonOutput, true)));
        } catch (IOException e) {
            System.out.println("Failed to connect to DaemonClient" +
                               Throwables.getStackTraceAsString(e));
            System.exit(1);
        }

        if (!daemonStarted) {
            new Thread(() -> {
                daemonStarted = true;

                DaemonMain daemonMain = new DaemonMain();
                daemonMain.run(new String[]{GradleVersion.current().getVersion()});;
            }, "GradleDaemonThread").start();
        }

        return START_NOT_STICKY;
    }

    private Notification setupNotification() {
        Intent stopIntent = new Intent(this, GradleDaemonService.class);
        stopIntent.setAction(ACTION_STOP_DAEMON);
        stopIntent.putExtra(EXTRA_NOTIFICATION_ID, 201);
        PendingIntent snoozePendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, createNotificationChannel())
                .setContentTitle("CodeAssist Gradle Daemon")
                .setSmallIcon(R.drawable.ic_stat_code)
                .setContentText("Running")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addAction(R.drawable.crash_ic_close, "STOP", snoozePendingIntent)
                .build();
    }

    private String createNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder("GradleDaemon",
                NotificationManagerCompat.IMPORTANCE_HIGH).setName("Gradle Daemon Service")
                .setDescription("Foreground service to keep the Gradle Daemon alive").build();

        NotificationManagerCompat.from(this).createNotificationChannel(channel);

        return "GradleDaemon";
    }
}