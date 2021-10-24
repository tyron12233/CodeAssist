package com.tyron.completion.provider;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.completion.CompileBatch;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

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

    }

    @NonNull
    public JavaCompilerService getCompiler(Project project) {

        Set<File> paths = project.getFileManager().fileClasspath();

       if (mProvider == null || changed(mCachedPaths, paths)) {
           mProvider = new JavaCompilerService(project, paths,
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

        JavaCompilerService compiler = getCompiler(project);
        project.getRJavaFiles().forEach((key, value) -> {
            try (CompileTask task = compiler.compile(value.toPath())) {
                Log.d(TAG, "Compiled " + task.root().getPackage());
            }
        });

        setIndexing(false);
        if (callback != null) {
            handler.post(callback);
        }
    }

    @NonNull
    public CompletionList complete(Project project, File file, String contents, long cursor) throws InterruptedException {
        // Do not request for completion if we're indexing
        if (mIndexing) {
            return CompletionList.EMPTY;
        }

        try {
            return new CompletionProvider(getCompiler(project)).complete(file, contents, cursor);
        } catch (Throwable e) {
            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
            mProvider = null;
        }
        return CompletionList.EMPTY;
    }

}
