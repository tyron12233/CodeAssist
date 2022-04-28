package com.tyron.completion.java.provider;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionList;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class CompletionEngine {
    private static final String TAG = CompletionEngine.class.getSimpleName();

    private final List<DiagnosticListener<? super JavaFileObject>> mDiagnosticListeners;
    private final DiagnosticListener<? super JavaFileObject> mInternalListener;
    private final HashSet<Object> mCachedPaths;

    private CachedCompletion cachedCompletion;

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
        mDiagnosticListeners = new ArrayList<>();
        mCachedPaths = new HashSet<>();
        mInternalListener = d -> {
            for (DiagnosticListener<? super JavaFileObject> listener : mDiagnosticListeners) {
                listener.report(d);
            }
        };
    }

    public void addDiagnosticListener(DiagnosticListener<? super JavaFileObject> listener) {
        mDiagnosticListeners.add(listener);
    }

    public void removeDiagnosticListener(DiagnosticListener<? super JavaFileObject> listener) {
        mDiagnosticListeners.remove(listener);
    }

    @NonNull


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
    public void index(Project project, JavaModule module, ILogger logger, Runnable callback) {
//        if (module instanceof AndroidModule) {
//            IncrementalAapt2Task task = new IncrementalAapt2Task((AndroidModule) module,
//                    ILogger.EMPTY, false);
//            try {
//                task.prepare(BuildType.DEBUG);
//                task.generateResourceClasses();
//            } catch (IOException | CompilationFailedException e) {
//                Log.e(TAG, "Failed to index with aapt2", e);
//            }
//        }
//        Set<File> newSet = new HashSet<>(module.getJavaFiles().values());
//
//        for (File library : module.getLibraries()) {
//            try {
//                JarFile jarFile = new JarFile(library);
//                newSet.add(library);
//            } catch (IOException e) {
//                FileUtils.deleteQuietly(library);
//                logger.warning("Library is corrupt, deleting jar file! " + library);
//            }
//        }
//
//        if (!changed(mCachedPaths, newSet)) {
//            setIndexing(false);
//            handler.post(callback);
//            return;
//        }
//
//        setIndexing(true);
//
//        JavaCompilerService compiler = getCompiler(project, module);
//
//        List<File> filesToIndex = new ArrayList<>(module.getJavaFiles().values());
//
//        if (!filesToIndex.isEmpty()) {
//            try (CompileTask task = compiler.compile(filesToIndex.stream()
//                    .map(File::toPath)
//                    .toArray(Path[]::new))) {
//                Log.d(TAG, "Index success.");
//            }
//        }
//
//        setIndexing(false);
//        if (callback != null) {
//            handler.post(callback);
//        }
    }

    public synchronized CompletionList complete(Project project,
                                                JavaModule module,
                                                File file,
                                                String contents,
                                                String prefix,
                                                int line,
                                                int column,
                                                long index) throws InterruptedException {
//        if (mIndexing) {
//            return CompletionList.EMPTY;
//        }
//

        return CompletionList.EMPTY;
    }

    @NonNull
    public synchronized CompletionList complete(Project project,
                                                JavaModule module,
                                                File file,
                                                String contents,
                                                long cursor) throws InterruptedException {
        // Do not request for completion if we're indexing
//        if (mIndexing) {
//            return CompletionList.EMPTY;
//        }
//
//        try {
//            JavaCompilerService compiler = getCompiler(project, module);
//            return new CompletionProvider(compiler)
//                    .complete(file, contents, cursor);
//        } catch (Throwable e) {
//            if (e instanceof InterruptedException) {
//                throw e;
//            }
//
//            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
//            mProvider = null;
//        }
        return CompletionList.EMPTY;
    }


}
