package com.tyron.code.compiler;

import android.net.Uri;

import com.android.sdklib.build.ApkBuilderMain;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.editor.log.LogViewModel;
import com.tyron.code.model.Project;
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

    private final LogViewModel log;
    private final Project mProject;
    private final ExecutorService service = Executors.newFixedThreadPool(1);

    public ApkBuilder(LogViewModel model, Project project) {
        log = model;
        mProject = project;
    }

    public void build(OnResultListener listener) {
        service.execute(() -> {
            try {
                doBuild();

                post(() -> listener.onComplete(true, "Build success."));
            } catch (IOException | CompilationFailedException e) {
                post(() -> listener.onComplete(false, e.getMessage()));
            }
        });
    }

    private void post(Runnable runnable) {
        ApplicationLoader.applicationHandler.post(runnable);
    }

    private void doBuild() throws IOException, CompilationFailedException{
        AAPT2Compiler aapt2Compiler = new AAPT2Compiler(mProject);
        aapt2Compiler.run();

        JavaCompiler javaCompiler = new JavaCompiler(log, mProject);
        javaCompiler.compile();

        D8Compiler d8Compiler = new D8Compiler(log, mProject);
        d8Compiler.compile();

        File binDir = new File(mProject.getBuildDirectory(), "bin");

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
