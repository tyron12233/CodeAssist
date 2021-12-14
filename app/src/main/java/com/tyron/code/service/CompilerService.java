package com.tyron.code.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tyron.builder.compiler.AndroidAppBuilder;
import com.tyron.builder.compiler.AndroidAppBundleBuilder;
import com.tyron.builder.compiler.ApkBuilder;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Builder;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.api.AndroidProject;
import com.tyron.builder.project.api.Project;
import com.tyron.code.R;
import com.tyron.code.util.ApkInstaller;

import java.io.File;
import java.util.concurrent.Executors;

public class CompilerService extends Service {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CompilerBinder mBinder = new CompilerBinder();

    public class CompilerBinder extends Binder {
        public CompilerService getCompilerService() {
            return CompilerService.this;
        }
    }

    private Project mProject;
    private ApkBuilder.OnResultListener onResultListener;
    private ILogger external;
    /**
     * Logger that  delegates logs to the external logger set
     */
    private final ILogger logger = new ILogger() {

        @Override
        public void info(DiagnosticWrapper wrapper) {
            if (external != null) {
                external.info(wrapper);
            }
        }

        @Override
        public void debug(DiagnosticWrapper wrapper) {
            if (external != null) {
                external.debug(wrapper);
            }
        }

        @Override
        public void warning(DiagnosticWrapper wrapper) {
            if (external != null) {
                external.warning(wrapper);
            }
        }

        @Override
        public void error(DiagnosticWrapper wrapper) {
            if (external != null) {
                external.error(wrapper);
            }
        }
    };

    private boolean shouldShowNotification = true;

    public void setShouldShowNotification(boolean val) {
        shouldShowNotification = val;
    }

    public void setLogger(ILogger logger) {
        this.external = logger;
    }

    public void setOnResultListener(ApkBuilder.OnResultListener listener) {
        onResultListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Notification notification = setupNotification();
        startForeground(201, notification);

        return START_STICKY;
    }

    private Notification setupNotification() {
        return new NotificationCompat.Builder(this, createNotificationChannel())
                .setContentTitle(getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentText("Preparing")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setProgress(100, 0, true)
                .build();
    }

    private void updateNotification(String title, String message, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "Compiler")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (progress != -1) {
            builder.setProgress(100, progress, false);
        }
        NotificationManagerCompat.from(this)
                .notify(201, builder.build());
    }

    private String createNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder("Compiler",
                NotificationManagerCompat.IMPORTANCE_HIGH)
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

    public void compile(Project project, BuildType type) {
        mProject = project;


        if (mProject == null) {
            if (onResultListener != null) {
                mMainHandler.post(() -> onResultListener.onComplete(false, "Failed to open project  (Have you opened a project?)"));
            }

            if (shouldShowNotification) {
                updateNotification("Compilation failed", "Unable to open project", -1);
            }
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            Builder<? extends Project> projectBuilder = getBuilderForProject(project, type);

            project.clear();
            project.index();

            boolean success = true;

            try {
                projectBuilder.build(type);
            } catch (Exception e) {
                mMainHandler.post(() -> onResultListener.onComplete(false, e.getMessage()));
                success = false;
            }

            if (success) {
                mMainHandler.post(() -> onResultListener.onComplete(true, "Success"));
            }

            projectBuilder.setTaskListener(this::updateNotification);

            String projectName = "Project";
            if (!success) {
                updateNotification(projectName,
                        getString(R.string.compilation_result_failed),
                        -1);
            } else {
                if (shouldShowNotification) {
                    mMainHandler.post(() -> {
                        NotificationCompat.Builder builder =
                                new NotificationCompat.Builder(this, "Compiler")
                                        .setSmallIcon(R.drawable.ic_launcher)
                                        .setContentTitle(projectName)
                                        .setContentText(getString(R.string.compilation_result_success));

                        if (type != BuildType.AAB) {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(ApkInstaller.uriFromFile(this,
                                    new File(mProject.getBuildDirectory(), "bin/signed.apk")),
                                    "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            PendingIntent pending = PendingIntent.getActivity(this,
                                    0,
                                    intent,
                                    PendingIntent.FLAG_IMMUTABLE);
                            builder.addAction(new NotificationCompat.Action(0,
                                    getString(R.string.compilation_button_install),
                                    pending));
                        }
                        NotificationManagerCompat.from(this)
                                .notify(201, builder.build());
                    });
                }
            }

            stopSelf();
            stopForeground(true);
        });
    }

    private Builder<? extends Project> getBuilderForProject(Project project, BuildType type) {
        if (project instanceof AndroidProject) {
            if (type == BuildType.AAB) {
                return new AndroidAppBundleBuilder((AndroidProject) project, logger);
            }
            return new AndroidAppBuilder((AndroidProject) project, logger);
        }
        return null;
    }
}
