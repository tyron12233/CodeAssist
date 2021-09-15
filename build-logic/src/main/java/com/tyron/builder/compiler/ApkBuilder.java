package com.tyron.builder.compiler;

import android.os.Handler;
import android.os.Looper;

import com.tyron.builder.compiler.apk.PackageTask;
import com.tyron.builder.compiler.apk.SignTask;
import com.tyron.builder.compiler.dex.D8Task;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.compiler.java.JavaTask;
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

    public void build(OnResultListener listener) {
        service.execute(() -> {
            try {
                long initialStart = System.currentTimeMillis();
                doBuild();

                post(() -> listener.onComplete(true, "Build success. Took " + (System.currentTimeMillis() - initialStart) + " ms"));
            } catch (IOException | CompilationFailedException e) {
                post(() -> listener.onComplete(false, e.getMessage()));
            }
        });
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private void post(Runnable runnable) {
        handler.post(runnable);
    }

    // TODO: run tasks in parallel if applicable
    private void doBuild() throws IOException, CompilationFailedException {
        List<Task> tasks = getTasks();

        for (Task task : tasks) {
            post(() -> mTaskListener.onTaskStarted(task.getName(), "Task started."));
            task.prepare(mProject, log);
            task.run();
        }
    }

    private List<Task> getTasks() {
        return Arrays.asList(
                new ManifestMergeTask(),
                new IncrementalAapt2Task(),
                new MergeSymbolsTask(),
                new JavaTask(),
                new D8Task(),
                new PackageTask(),
                new SignTask());
    }
}
