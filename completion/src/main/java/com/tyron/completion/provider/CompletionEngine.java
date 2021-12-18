package com.tyron.completion.provider;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.apache.commons.io.FileUtils;
import org.openjdk.javax.tools.DiagnosticListener;
import org.openjdk.javax.tools.JavaFileObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class CompletionEngine {
    private static final String TAG = CompletionEngine.class.getSimpleName();

    private final List<DiagnosticListener<? super JavaFileObject>> mDiagnosticListeners;
    private final DiagnosticListener<? super JavaFileObject> mInternalListener;
    private final Set<File> mCachedPaths;
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
    public JavaCompilerService getCompiler(Project project, JavaModule module) {

        List<Module> dependencies = new ArrayList<>();
        if (project != null) {
            dependencies.addAll(project.getDependencies(module));
        }

        Set<File> paths = new HashSet<>();
        paths.addAll(module.getJavaFiles().values());
        paths.addAll(module.getLibraries());

        for (Module dependency : dependencies) {
            if (dependency instanceof JavaModule) {
                paths.addAll(((JavaModule) dependency).getJavaFiles().values());
                paths.addAll(((JavaModule) dependency).getLibraries());
            }
        }

        if (mProvider == null || changed(mCachedPaths, paths)) {
            mProvider = new JavaCompilerService(project, paths,
                    Collections.emptySet(), Collections.emptySet());

            mCachedPaths.clear();
            mCachedPaths.addAll(paths);
            mProvider.setDiagnosticListener(mInternalListener);
            mProvider.setCurrentModule(module);
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
    public void index(Project project, JavaModule module, ILogger logger, Runnable callback) {
        if (module instanceof AndroidModule) {
            IncrementalAapt2Task task = new IncrementalAapt2Task((AndroidModule) module,
                    ILogger.EMPTY, false);
            try {
                task.prepare(BuildType.DEBUG);
                task.generateResourceClasses();
            } catch (IOException | CompilationFailedException e) {
                Log.e(TAG, "Failed to index with aapt2", e);
            }
        }
        Set<File> newSet = new HashSet<>(module.getJavaFiles().values());

        for (File library : module.getLibraries()) {
            try {
                JarFile jarFile = new JarFile(library);
                newSet.add(library);
            } catch (IOException e) {
                FileUtils.deleteQuietly(library);
                logger.warning("Library is corrupt, deleting jar file! " + library);
            }
        }

        if (!changed(mCachedPaths, newSet)) {
            setIndexing(false);
            handler.post(callback);
            return;
        }

        setIndexing(true);

        JavaCompilerService compiler = getCompiler(project, module);

        List<File> filesToIndex = new ArrayList<>(module.getJavaFiles().values());

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

    public synchronized CompletionList complete(Project project,
                                                JavaModule module,
                                                File file,
                                                String contents,
                                                String prefix,
                                                int line,
                                                int column,
                                                long index) throws InterruptedException {
        if (mIndexing) {
            return CompletionList.EMPTY;
        }

        if (isIncrementalCompletion(cachedCompletion, file, prefix, line, column)) {
            Log.d(TAG, "Using incremental completion");
            String partialIdentifier = partialIdentifier(prefix, prefix.length());
            List<CompletionItem> narrowedList = cachedCompletion.getCompletionList().items.stream()
                    .filter(item -> {
                        String label = item.label;
                        if (label.contains("(")) {
                            label = label.substring(0, label.indexOf('('));
                        }
                        if (label.length() < partialIdentifier.length()) {
                            return false;
                        }
                        return FuzzySearch.partialRatio(label, partialIdentifier) > 90;
                    })
                    .collect(Collectors.toList());
            CompletionList completionList = new CompletionList();
            completionList.items = narrowedList;
            return completionList;
        }

        try {
            CompletionList complete = new CompletionProvider(getCompiler(project, module))
                    .complete(file, contents, index);
            String newPrefix = prefix;
            if (prefix.contains(".")) {
                newPrefix = partialIdentifier(prefix, prefix.length());
            }
            cachedCompletion = new CachedCompletion(file, line, column, newPrefix, complete);
            return complete;
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                throw e;
            }

            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
            mProvider = null;
        }
        return CompletionList.EMPTY;
    }

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    @NonNull
    public synchronized CompletionList complete(Project project,
                                                JavaModule module,
                                                File file,
                                                String contents,
                                                long cursor) throws InterruptedException {
        // Do not request for completion if we're indexing
        if (mIndexing) {
            return CompletionList.EMPTY;
        }

        try {
            JavaCompilerService compiler = getCompiler(project, module);
            return new CompletionProvider(compiler)
                    .complete(file, contents, cursor);
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                throw e;
            }

            Log.d(TAG, "Completion failed: " + Log.getStackTraceString(e) + " Clearing cache.");
            mProvider = null;
        }
        return CompletionList.EMPTY;
    }

    private boolean isIncrementalCompletion(CachedCompletion cachedCompletion,
                                            File file,
                                            String prefix,
                                            int line, int column) {
        prefix = partialIdentifier(prefix, prefix.length());

        if (cachedCompletion == null) {
            return false;
        }

        if (!file.equals(cachedCompletion.getFile())) {
            return false;
        }

        if (prefix.endsWith(".")) {
            return false;
        }

        if (cachedCompletion.getLine() != line) {
            return false;
        }

        if (cachedCompletion.getColumn() > column) {
            return false;
        }

        if (!prefix.startsWith(cachedCompletion.getPrefix())) {
            return false;
        }

        if (prefix.length() - cachedCompletion.getPrefix().length() !=
                column - cachedCompletion.getColumn()) {
            return false;
        }

        return true;
    }
}
