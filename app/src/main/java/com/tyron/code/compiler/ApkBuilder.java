package com.tyron.code.compiler;

import android.net.Uri;

import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.compiler.dex.D8Compiler;
import com.tyron.code.compiler.incremental.resource.IncrementalAAPT2Compiler;
import com.tyron.code.compiler.java.JavaCompiler;
import com.tyron.code.compiler.manifest.ManifestMergeTask;
import com.tyron.code.compiler.resource.AAPT2Compiler;
import com.tyron.code.compiler.symbol.SymbolProcessor;
import com.tyron.code.model.Project;
import com.tyron.code.service.ILogger;
import com.tyron.code.util.exception.CompilationFailedException;

import java.io.File;
import java.io.IOException;
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

    private void post(Runnable runnable) {
        ApplicationLoader.applicationHandler.post(runnable);
    }

    // TODO: run tasks in parallel if applicable
    private void doBuild() throws IOException, CompilationFailedException {

        Task manifestMergeTask = new ManifestMergeTask();
        manifestMergeTask.prepare(mProject, log);
        manifestMergeTask.run();

        post(() -> mTaskListener.onTaskStarted("AAPT2", "Compiling resources"));
        IncrementalAAPT2Compiler aapt2Compiler = new IncrementalAAPT2Compiler(mProject, log);
        aapt2Compiler.run();

        post(() -> mTaskListener.onTaskStarted("SymbolProcessor", "Generating symbols"));
        SymbolProcessor processor = new SymbolProcessor(mProject, log);
        processor.run();

        post(() -> mTaskListener.onTaskStarted("JAVAC", "Compiling java files"));
        JavaCompiler javaCompiler = new JavaCompiler(log, mProject);
        javaCompiler.compile();

        post(() -> mTaskListener.onTaskStarted("D8", "Dexing/Merging source files"));
        D8Compiler d8Compiler = new D8Compiler(log, mProject);
        d8Compiler.compile();

        File binDir = new File(mProject.getBuildDirectory(), "bin");

        post(() -> mTaskListener.onTaskStarted("APK Builder", "Packaging APK"));
        try {
            com.android.sdklib.build.ApkBuilder builder = new com.android.sdklib.build.ApkBuilder(
                    binDir + "/generated.apk",
                    binDir + "/generated.apk.res",
                    binDir + "/classes.dex",
                    null,
                    null
            );

            File[] binFiles = binDir.listFiles();
            if (binFiles != null) {
                for (File file : binFiles) {
                    if (!file.getName().equals("classes.dex") && file.getName().endsWith(".dex")) {
                        builder.addFile(file, Uri.parse(file.getAbsolutePath()).getLastPathSegment());
                    }
                }
            }

            for (File lib : mProject.getLibraries()) {
                builder.addResourcesFromJar(lib);
            }

            builder.setDebugMode(false);
            builder.sealApk();
        } catch (ApkCreationException | SealedApkException | DuplicateFileException e) {
            throw new CompilationFailedException(e);
        }

        post(() -> mTaskListener.onTaskStarted("APK Signer", "Signing APK"));
        ApkSigner signer = new ApkSigner(
                binDir + "/generated.apk",
                binDir + "/signed.apk",
                ApkSigner.Mode.TEST
        );
        try {
            signer.sign();
        } catch (Exception exception) {
            throw new CompilationFailedException(exception);
        }
    }
}
