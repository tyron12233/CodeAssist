package com.tyron.builder.compiler;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tyron.builder.compiler.aab.AabTask;
import com.tyron.builder.compiler.apk.PackageTask;
import com.tyron.builder.compiler.apk.SignTask;
import com.tyron.builder.compiler.dex.R8Task;
import com.tyron.builder.compiler.firebase.GenerateFirebaseConfigTask;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.compiler.incremental.kotlin.IncrementalKotlinCompiler;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.compiler.log.InjectLoggerTask;
import com.tyron.builder.compiler.manifest.ManifestMergeTask;
import com.tyron.builder.compiler.symbol.MergeSymbolsTask;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;
import com.tyron.builder.model.ProjectSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Main entry point for building apk files, this class does all
 *  * the necessary operations for building apk files such as compiling resources,
 *  * compiling java files, dexing and merging
 */
public class ApkBuilder {

    public interface OnResultListener {
        void onComplete(boolean success, String message);
    }

    public interface TaskListener {
        void onTaskStarted(String name, String message, int progress);
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

                post(() -> listener.onComplete(true,
                        "Build success. Took " + (System.currentTimeMillis() - initialStart) + " ms"));
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
        List<Task> tasks = getTasks(type);
        List<Task> tasksRan = new ArrayList<>();
        int totalTasks = tasks.size();

        for (int i = 0; i < totalTasks; i++) {
            Task task = tasks.get(i);
            final float currentPos = i;
            try {
                post(() -> mTaskListener.onTaskStarted(task.getName(), "Task started.",
                        (int) ((currentPos / (float) totalTasks) * 100f)));
                task.prepare(type);
                task.run();
            } catch (CompilationFailedException | IOException e) {
                tasksRan.forEach(Task::clean);
                throw e;
            }

            tasksRan.add(task);
        }

        tasks.forEach(Task::clean);
    }

    private List<Task> getTasks(BuildType type) {
        if (BuildType.AAB == type) {
            return getAabTasks();
        } else {
            return getApkTasks(type);
        }
    }

    private List<Task> getApkTasks(BuildType type) {
        List<Task> task = new ArrayList<>();
//        task.add(new CleanTask(mProject, log));
//        task.add(new ManifestMergeTask(mProject, log));
//        task.add(new GenerateFirebaseConfigTask(mProject, log));
//        if (type == BuildType.DEBUG) {
//            task.add(new InjectLoggerTask(mProject, log));
//        }
//        task.add(new IncrementalAapt2Task(mProject, log, false));
//        task.add(new MergeSymbolsTask(mProject, log));
//        task.add(new IncrementalKotlinCompiler(mProject, log));
//        task.add(new IncrementalJavaTask(mProject, log));
//        if (mProject.getSettings().getBoolean(ProjectSettings.USE_R8, false) &&
//                type == BuildType.RELEASE) {
//            task.add(new R8Task(mProject, log));
//        } else {
//            task.add(new IncrementalD8Task(mProject, log));
//        }
//        task.add(new PackageTask(mProject, log));
//        task.add(new SignTask(mProject, log));
        return task;
    }


    private List<Task> getAabTasks() {
        List<Task> tasks = new ArrayList<>();
//        tasks.add(new CleanTask(mProject, log));
//        tasks.add(new ManifestMergeTask(mProject, log));
//        tasks.add(new GenerateFirebaseConfigTask(mProject, log));
//        tasks.add(new IncrementalAapt2Task(mProject, log, true));
//        tasks.add(new MergeSymbolsTask(mProject, log));
//        tasks.add(new IncrementalKotlinCompiler(mProject, log));
//        tasks.add(new IncrementalJavaTask(mProject, log));
//        if (mProject.getSettings().getBoolean(ProjectSettings.USE_R8, false)) {
//            tasks.add(new R8Task(mProject, log));
//        } else {
//            tasks.add(new IncrementalD8Task(mProject, log));
//        }
//        tasks.add(new AabTask(mProject, log));
        return tasks;
    }
}
