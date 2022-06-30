package com.tyron.code.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;
import com.tyron.builder.compiler.AndroidAppBuilder;
import com.tyron.builder.compiler.AndroidAppBundleBuilder;
import com.tyron.builder.compiler.ApkBuilder;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Builder;
import com.tyron.builder.compiler.ProjectBuilder;
import com.tyron.builder.execution.MultipleBuildFailures;
import com.tyron.builder.initialization.ReportedException;
import com.tyron.builder.internal.buildoption.BuildOption;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.console.AnsiConsole;
import com.tyron.builder.internal.logging.console.ColorMap;
import com.tyron.builder.internal.logging.console.Console;
import com.tyron.builder.internal.logging.console.DefaultColorMap;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.internal.logging.sink.OutputEventRenderer;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;
import com.tyron.builder.internal.nativeintegration.console.FallbackConsoleMetaData;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.launcher.ProjectLauncher;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.BuildConfig;
import com.tyron.code.R;
import com.tyron.code.ui.editor.log.AppLogFragment;
import com.tyron.code.util.ApkInstaller;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.progress.ProgressIndicator;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Collections;

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
        return new NotificationCompat.Builder(this, createNotificationChannel())
                .setContentTitle(getString(R.string.app_name)).setSmallIcon(R.drawable.ic_stat_code)
                .setContentText("Preparing").setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true).setProgress(100, 0, true).build();
    }

    private void updateNotification(String title, String message, int progress) {
        updateNotification(title, message, progress, NotificationCompat.PRIORITY_LOW);
    }

    private void updateNotification(String title, String message, int progress, int priority) {
        new Handler(Looper.getMainLooper()).post(() -> {
            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(this, "Compiler").setContentTitle(title)
                            .setContentText(message).setSmallIcon(R.drawable.ic_stat_code)
                            .setPriority(priority);
            if (progress != -1) {
                builder.setProgress(100, progress, false);
            }
            NotificationManagerCompat.from(this).notify(201, builder.build());
        });
    }

    private String createNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder("Compiler",
                NotificationManagerCompat.IMPORTANCE_HIGH).setName("Compiler service")
                .setDescription("Foreground notification for the compiler").build();

        NotificationManagerCompat.from(this).createNotificationChannel(channel);

        return "Compiler";
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void compile(Project project, BuildType type) {

        if (true) {
            ProgressManager.getInstance()
                    .runNonCancelableAsync(() -> compileWithBuilderApi(project, type));
            return;
        }

        mProject = project;


        if (mProject == null) {
            if (onResultListener != null) {
                mMainHandler.post(() -> onResultListener.onComplete(false,
                        "Failed to open " + "project  (Have you opened a project?)"));
            }

            if (shouldShowNotification) {
                updateNotification("Compilation failed", "Unable to open project", -1,
                        NotificationCompat.PRIORITY_HIGH);
            }
            return;
        }

        project.setCompiling(true);
        ProgressIndicator indicator = new ProgressIndicator();
        ProgressManager.getInstance().runAsync(() -> {
            try {
                if (true) {
                    buildProject(project, type);
                } else {
                    buildMainModule(project, type);
                }
            } finally {
                project.setCompiling(false);
            }
        }, i -> {

        }, indicator);
    }

    private void log(LogLevel logLevel, CharSequence contents) {
        if (logLevel == null) {
            logLevel = LogLevel.LIFECYCLE;
        }
        switch (logLevel) {
            case DEBUG:
            case INFO:
            case LIFECYCLE:
                logger.debug(contents);
                break;
            case WARN:
                logger.warning(contents);
                break;
            case QUIET:
                logger.verbose(contents.toString());
                break;
            case ERROR:
                logger.error(contents);
                break;
        }
    }

    private void compileWithBuilderApi(Project project, BuildType type) {
        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setVfsVerboseLogging(getVerboseVfsLogging());
        startParameter.setShowStacktrace(getShowStacktrace());
        startParameter.setParallelProjectExecutionEnabled(true);
        startParameter.setConfigurationCache(BuildOption.Value.value(true));
        startParameter.setConfigurationCacheDebug(true);
        startParameter.setWarningMode(WarningMode.All);
        startParameter.setBuildCacheEnabled(true);
        File rootFile = project.getRootFile();
        startParameter.setProjectDir(rootFile);
        startParameter.setLogLevel(getLogLevel());
        startParameter.setConsoleOutput(ConsoleOutput.Rich);
        startParameter.setGradleUserHomeDir(new File(getCacheDir(), ".gradle"));
        startParameter.setTaskNames(Collections.singletonList(":app:assemble"));

        ProjectLauncher projectLauncher = new ProjectLauncher(startParameter, null);

        LoggingManagerInternal loggingManagerInternal =
                projectLauncher.getGlobalServices().get(LoggingManagerInternal.class);
        loggingManagerInternal.captureSystemSources();
        loggingManagerInternal.attachConsole(AppLogFragment.outputStream, AppLogFragment.errorOutputStream, ConsoleOutput.Verbose, new ConsoleMetaData() {
            @Override
            public boolean isStdOut() {
                return true;
            }

            @Override
            public boolean isStdErr() {
                return true;
            }

            @Override
            public int getCols() {
                return 60;
            }

            @Override
            public int getRows() {
                return 20;
            }

            @Override
            public boolean isWrapStreams() {
                return false;
            }
        });

        try {
            projectLauncher.execute();
            mMainHandler.post(() -> onResultListener.onComplete(true, "Success"));
        } catch (Throwable t) {
            String message;
            if (t instanceof MultipleBuildFailures) {
                message = Log.getStackTraceString(t.getCause());
            } else if (t instanceof ReportedException) {
                message = "";
            } else {
                message = t.getMessage();
            }
            mMainHandler.post(() -> onResultListener.onComplete(false, message));
        }

        loggingManagerInternal.stop();

        stopSelf();
        stopForeground(true);
    }

    private boolean getVerboseVfsLogging() {
        return ApplicationLoader.getDefaultPreferences().getBoolean(SharedPreferenceKeys.GRADLE_VERBOSE_VFS_LOGGING, false);
    }

    private ShowStacktrace getShowStacktrace() {
        String showStacktrace = ApplicationLoader.getDefaultPreferences()
                .getString(SharedPreferenceKeys.GRADLE_STACKTRACE_MODE, "LIFECYCLE");
        switch (showStacktrace) {
            case "ALWAYS": return ShowStacktrace.ALWAYS;
            case "ALWAYS_FULL": return ShowStacktrace.ALWAYS_FULL;
            default:
            case "INTERNAL_EXCEPTIONS": return ShowStacktrace.INTERNAL_EXCEPTIONS;
        }
    }

    private LogLevel getLogLevel() {
        String logLevel = ApplicationLoader.getDefaultPreferences()
                .getString(SharedPreferenceKeys.GRADLE_LOG_LEVEL, "LIFECYCLE");
        switch (logLevel) {
            case "ERROR": return LogLevel.ERROR;
            case "DEBUG": return LogLevel.DEBUG;
            case "INFO": return LogLevel.INFO;
            case "QUIET": return LogLevel.QUIET;
            case "WARN": return LogLevel.WARN;
            case "LIFECYCLE":
            default: return LogLevel.LIFECYCLE;
        }
    }

    private void buildProject(Project project, BuildType type) {
        boolean success = true;

        try {
            ProjectBuilder projectBuilder = new ProjectBuilder(project, logger);
            projectBuilder.setTaskListener(this::updateNotification);
            projectBuilder.build(type);
        } catch (Throwable e) {
            String message;
            if (BuildConfig.DEBUG) {
                message = Log.getStackTraceString(e);
            } else {
                message = e.getMessage();
            }
            mMainHandler.post(() -> onResultListener.onComplete(false, message));
            success = false;
        }

        report(success, type, project.getMainModule());
    }

    private void buildMainModule(Project project, BuildType type) {
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

        report(success, type, module);
    }

    private void report(boolean success, BuildType type, Module module) {
        if (success) {
            mMainHandler.post(() -> onResultListener.onComplete(true, "Success"));
        }


        String projectName = "Project";
        if (!success) {
            updateNotification(projectName, getString(R.string.compilation_result_failed), -1,
                    NotificationCompat.PRIORITY_HIGH);
        } else {
            if (shouldShowNotification) {
                mMainHandler.post(() -> {
                    NotificationCompat.Builder builder =
                            new NotificationCompat.Builder(this, "Compiler")
                                    .setSmallIcon(R.drawable.ic_stat_code)
                                    .setContentTitle(projectName)
                                    .setContentText(getString(R.string.compilation_result_success));

                    if (type != BuildType.AAB) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(ApkInstaller.uriFromFile(this,
                                new File(module.getBuildDirectory(), "bin/signed.apk")),
                                "application/vnd.android.package-archive");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        PendingIntent pending = PendingIntent
                                .getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
                        builder.addAction(new NotificationCompat.Action(0,
                                getString(R.string.compilation_button_install), pending));
                    }
                    NotificationManagerCompat.from(this).notify(201, builder.build());
                });
            }
        }

        stopSelf();
        stopForeground(true);
    }

    private Builder<? extends Module> getBuilderForProject(Module module, BuildType type) {
        if (module instanceof AndroidModule) {
            if (type == BuildType.AAB) {
                return new AndroidAppBundleBuilder(mProject, (AndroidModule) module, logger);
            }
            return new AndroidAppBuilder(mProject, (AndroidModule) module, logger);
        }
        return null;
    }

    public static class AndroidStyledTextOutput implements StyledTextOutput {

        private final SpannableStringBuilder buffer = new SpannableStringBuilder();

        @Override
        public StyledTextOutput append(char c) {
            buffer.append(c);
            return this;
        }

        @Override
        public StyledTextOutput append(CharSequence csq) {
            buffer.append(csq);
            return this;
        }

        @Override
        public StyledTextOutput append(CharSequence csq, int start, int end) {
            buffer.append(csq, start, end);
            return this;
        }

        @Override
        public StyledTextOutput style(Style style) {
            buffer.setSpan(getForStyle(style), 0, buffer.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return this;
        }

        @Override
        public StyledTextOutput withStyle(Style style) {
            return this;
        }

        @Override
        public StyledTextOutput text(Object text) {
            append(text == null ? "null" : text.toString());
            return this;
        }

        @Override
        public StyledTextOutput println(Object text) {
            append(text == null ? "null" : text.toString());
            return this;
        }

        @Override
        public StyledTextOutput format(String pattern, Object... args) {
            text(String.format(pattern, args));
            return this;
        }

        @Override
        public StyledTextOutput formatln(String pattern, Object... args) {
            format(pattern, args);
            println();
            return this;
        }

        @Override
        public StyledTextOutput println() {
            text("\n");
            return this;
        }

        @Override
        public StyledTextOutput exception(Throwable throwable) {
            return this;
        }

        private CharacterStyle getForStyle(Style style) {
            switch (style) {
                case Header:
                case UserInput:
                    return new StyleSpan(Typeface.BOLD);
                case SuccessHeader:
                case Success:
                case Identifier:
                    return new ForegroundColorSpan(Color.GREEN);
                case FailureHeader:
                case Failure:
                case Error:
                    return new ForegroundColorSpan(Color.RED);
                case ProgressStatus:
                case Description:
                case Info:
                    return new ForegroundColorSpan(Color.YELLOW);
                case Normal:
                default:
                    return new ForegroundColorSpan(Color.WHITE);
            }
        }

        public CharSequence getBufferString() {
            CharSequence string = new SpannableStringBuilder(buffer);
            buffer.clear();
            return string;
        }
    }
}
