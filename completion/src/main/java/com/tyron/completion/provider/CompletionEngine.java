package com.tyron.completion.provider;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.builder.project.api.JavaProject;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CompletionEngine {
    private static final String TAG = CompletionEngine.class.getSimpleName();

    private final Set<File> mCachedPaths = new HashSet<>();

    public static volatile CompletionEngine Instance = null;

    public static synchronized CompletionEngine getInstance() {
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
    public JavaCompilerService getCompiler(JavaProject project) {

        Set<File> paths = new HashSet<>();
        paths.addAll(project.getJavaFiles().values());
        paths.addAll(project.getLibraries());

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
    public void index(JavaProject project, Runnable callback) {

        Set<File> newSet = new HashSet<>();
        newSet.addAll(project.getJavaFiles().values());
        newSet.addAll(project.getLibraries());
        if (!changed(mCachedPaths, newSet)) {
            setIndexing(false);
            handler.post(callback);
            return;
        }

        setIndexing(true);

        JavaCompilerService compiler = getCompiler(project);

        List<File> filesToIndex = new ArrayList<>(project.getJavaFiles().values());

        if (!filesToIndex.isEmpty()) {
            try (CompileTask task = compiler.compile(filesToIndex.stream()
                    .map(File::toPath)
                    .toArray(Path[]::new))) {
                Log.d(TAG, "Index success.");
            }
        }

        setIndexing(false);
        if (callback != null) {
            handler.post(callback);
        }
    }

    @NonNull
    public synchronized CompletionList complete(JavaProject project, File file, String contents, long cursor) throws InterruptedException {
        // Do not request for completion if we're indexing
        if (mIndexing) {
            return CompletionList.EMPTY;
        }

        try {
            return new CompletionProvider(getCompiler(project)).complete(file, contents, cursor);
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                throw e;
            }

            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
            mProvider = null;
        }
        return CompletionList.EMPTY;
    }
}
