package com.tyron.completion.provider;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class CompletionEngine {
    private static final String TAG = CompletionEngine.class.getSimpleName();

    public static CompletionEngine Instance = null;

    public static CompletionEngine getInstance() {
        if (Instance == null) {
            Instance = new CompletionEngine();
        }
        return Instance;
    }

    private JavaCompilerService mProvider;
    private static boolean mIndexing;

    private CompletionEngine() {
        getCompiler();
    }

    @NonNull
    public JavaCompilerService getCompiler() {
        if (mProvider != null) {
            return mProvider;
        }

        mProvider = new JavaCompilerService(
                FileManager.getInstance().fileClasspath(),
                Collections.emptySet(),
                Collections.emptySet()
        );
        return mProvider;
    }

    /**
     * Disable subsequent completions
     */
    public static void setIndexing(boolean val) {
        mIndexing = val;
    }

    public static boolean isIndexing() {
        return mIndexing;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("NewApi")
    public void index(Project project, Runnable callback) {
        setIndexing(true);
        project.clear();

        project.getLibraries();
        JavaCompilerService compiler = getCompiler();
        Set<File> filesToIndex = new HashSet<>(project.getJavaFiles().values());
        filesToIndex.addAll(project.getRJavaFiles().values());

        for (File file : filesToIndex) {
            try (CompileTask task = compiler.compile(file.toPath())) {
                Log.d(getClass().getSimpleName(), file.getName() + " compiled successfully");
            }
        }
        setIndexing(false);
        if (callback != null) {
            handler.post(callback);
        }
    }

    @NonNull
    public CompletionList complete(File file, long cursor) {
        // Do not request for completion if we're indexing
        if (mIndexing) {
            return CompletionList.EMPTY;
        }

        try {
            return new CompletionProvider(mProvider).complete(file, cursor);
        } catch (RuntimeException | AssertionError e) {
            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
            mProvider = null;
            index(FileManager.getInstance().getCurrentProject(), null);
        }
        return CompletionList.EMPTY;
    }

}
