package com.tyron.code.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tyron.builder.compiler.AndroidAppBuilder;
import com.tyron.builder.compiler.AndroidAppBundleBuilder;
import com.tyron.builder.compiler.ApkBuilder;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Builder;
import com.tyron.builder.compiler.ProjectBuilder;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.BuildConfig;
import com.tyron.code.R;
import com.tyron.code.util.ApkInstaller;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;

public class CompilerService extends Service {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CompilerBinder mBinder = new CompilerBinder(this);

    public static class CompilerBinder extends Binder {

        private final WeakReference<CompilerService> mServiceReference;

        public CompilerBinder(CompilerService service) {
            mServiceReference = new WeakReference<>(service);
        }

        public CompilerService getCompilerService() {
            return mServiceReference.get();
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
        return new NotificationCompat.Builder(this, createNotificationChannel()).setContentTitle(getString(R.string.app_name)).setSmallIcon(R.drawable.ic_launcher).setContentText("Preparing").setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(true).setProgress(100, 0, true).build();
    }

    private void updateNotification(String title, String message, int progress) {
        updateNotification(title, message, progress, NotificationCompat.PRIORITY_LOW);
    }

    private void updateNotification(String title, String message, int progress, int priority) {
        new Handler(Looper.getMainLooper()).post(() -> {
            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(this, "Compiler").setContentTitle(title).setContentText(message).setSmallIcon(R.drawable.ic_launcher).setPriority(priority);
            if (progress != -1) {
                builder.setProgress(100, progress, false);
            }
            NotificationManagerCompat.from(this).notify(201, builder.build());
        });
    }

    private String createNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder("Compiler",
                NotificationManagerCompat.IMPORTANCE_HIGH).setName("Compiler service").setDescription("Foreground notification for the compiler").build();

        NotificationManagerCompat.from(this).createNotificationChannel(channel);

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
                mMainHandler.post(() -> onResultListener.onComplete(false, "Failed to open " +
                        "project  (Have you opened a project?)"));
            }

            if (shouldShowNotification) {
                updateNotification("Compilation failed", "Unable to open project", -1,
                        NotificationCompat.PRIORITY_HIGH);
            }
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            Module module = project.getMainModule();
            Builder<? extends Module> projectBuilder = getBuilderForProject(module, type);

            module.clear();
            module.index();

            boolean success = true;

            projectBuilder.setTaskListener(this::updateNotification);

            try {
                projectBuilder.build(type);
            } catch (Exception e) {
                String message;
                if (BuildConfig.DEBUG) {
                    message = Log.getStackTraceString(e);
                } else {
                    message = e.getMessage();
                }
                mMainHandler.post(() -> onResultListener.onComplete(false, message));
                success = false;
            }

            if (success) {
                mMainHandler.post(() -> onResultListener.onComplete(true, "Success"));
            }


            String projectName = "Project";
            if (!success) {
                updateNotification(projectName, getString(R.string.compilation_result_failed), -1
                        , NotificationCompat.PRIORITY_HIGH);
            } else {
                if (shouldShowNotification) {
                    mMainHandler.post(() -> {
                        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                                "Compiler").setSmallIcon(R.drawable.ic_launcher).setContentTitle(projectName).setContentText(getString(R.string.compilation_result_success));

                        if (type != BuildType.AAB) {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(ApkInstaller.uriFromFile(this,
                                    new File(module.getBuildDirectory(), "bin/signed.apk")),
                                    "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                                    PendingIntent.FLAG_IMMUTABLE);
                            builder.addAction(new NotificationCompat.Action(0,
                                    getString(R.string.compilation_button_install), pending));
                        }
                        NotificationManagerCompat.from(this).notify(201, builder.build());
                    });
                }
            }

            stopSelf();
            stopForeground(true);
        });
    }

    private Builder<? extends Module> getBuilderForProject(Module module, BuildType type) {
        if (module instanceof AndroidModule) {
            if (type == BuildType.AAB) {
                return new AndroidAppBundleBuilder((AndroidModule) module, logger);
            }
            return new AndroidAppBuilder((AndroidModule) module, logger);
        }
        return null;
    }
}
