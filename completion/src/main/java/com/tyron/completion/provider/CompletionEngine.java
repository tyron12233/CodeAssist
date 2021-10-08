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

    private final Set<File> mCachedPaths = new HashSet<>();

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

        Set<File> paths = FileManager.getInstance().fileClasspath();

       if (changed(mCachedPaths, paths) || mProvider == null) {
           mProvider = new JavaCompilerService(paths,
                   Collections.emptySet(), Collections.emptySet());

           mCachedPaths.clear();
           mCachedPaths.addAll(paths);

           Log.d(TAG, "Class path changed, creating a new compiler");
       }

       return mProvider;
    }

    private boolean changed(Set<File> oldFiles, Set<File> newFiles) {
        if (oldFiles.size() != newFiles.size()) {
            return true;
        }

        for (File oldFile : oldFiles) {
            if (!newFiles.contains(oldFile)) {
                return true;
            }
        }

        for (File newFile : newFiles) {
            if (!oldFiles.contains(newFile)) {
                return true;
            }
        }

        return false;
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
        project.getJavaFiles();
        project.getKotlinFiles();
        project.getRJavaFiles();
        project.getLibraries();
//        JavaCompilerService compiler = getCompiler();
//        Set<File> filesToIndex = new HashSet<>(project.getJavaFiles().values());
//        filesToIndex.addAll(project.getRJavaFiles().values());
//        for (File file : filesToIndex) {
//            if (file == null || !file.exists()) {
//                continue;
//            }
//            try (CompileTask task = compiler.compile(file.toPath())) {
//                Log.d(getClass().getSimpleName(), file.getName() + " compiled successfully");
//            }
//        }
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
            return new CompletionProvider(getCompiler()).complete(file, cursor);
        } catch (RuntimeException | AssertionError e) {
            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
            mProvider = null;
            index(FileManager.getInstance().getCurrentProject(), null);
        }
        return CompletionList.EMPTY;
    }

}
