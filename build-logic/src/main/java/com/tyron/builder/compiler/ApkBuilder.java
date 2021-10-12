package com.tyron.builder.compiler;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tyron.builder.compiler.apk.PackageTask;
import com.tyron.builder.compiler.apk.SignTask;
import com.tyron.builder.compiler.firebase.GenerateFirebaseConfigTask;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.compiler.incremental.kotlin.IncrementalKotlinCompiler;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.compiler.log.InjectLoggerTask;
import com.tyron.builder.compiler.manifest.ManifestMergeTask;
import com.tyron.builder.compiler.symbol.MergeSymbolsTask;
import com.tyron.builder.model.Project;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.exception.CompilationFailedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main entry point for building apk files, this class does all
 * the necessary operations for building apk files such as compiling resources,
 * compiling java files, dexing and merging
 */
public class ApkBuilder {

    public interface OnResultListener {
        void onComplete(boolean success, String message);
    }

    public interface TaskListener {
        void onTaskStarted(String name, String message);
    }

    private final ILogger log;
    private final Project mProject;
    private final ExecutorService service = Executors.newFixedThreadPool(1);

    private TaskListener mTaskListener;

    public ApkBuilder(ILogger model, Project project) {
        log = model;
        mProject = project;
    }

    public void setTaskListener(TaskListener listener) {
        mTaskListener = listener;
    }

    public void build(BuildType type, OnResultListener listener) {
        service.execute(() -> {
            try {
                long initialStart = System.currentTimeMillis();
                doBuild(type);

                post(() -> listener.onComplete(true, "Build success. Took " + (System.currentTimeMillis() - initialStart) + " ms"));
            } catch (IOException | CompilationFailedException e) {
                post(() -> listener.onComplete(false, Log.getStackTraceString(e)));
            }
        });
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private void post(Runnable runnable) {
        handler.post(runnable);
    }

    // TODO: run tasks in parallel if applicable
    private void doBuild(BuildType type) throws IOException, CompilationFailedException {
        List<Task> tasks = getTasks();

        for (Task task : tasks) {
            post(() -> mTaskListener.onTaskStarted(task.getName(), "Task started."));
            task.prepare(mProject, log, type);
            task.run();
        }
    }

    private List<Task> getTasks() {
        return Arrays.asList(
                new CleanTask(),
                new ManifestMergeTask(),
                new GenerateFirebaseConfigTask(),
                new IncrementalAapt2Task(),
                new MergeSymbolsTask(),
                new InjectLoggerTask(),
                new IncrementalKotlinCompiler(),
                new IncrementalJavaTask(),
                new IncrementalD8Task(),
                new PackageTask(),
                new SignTask());
    }
}
