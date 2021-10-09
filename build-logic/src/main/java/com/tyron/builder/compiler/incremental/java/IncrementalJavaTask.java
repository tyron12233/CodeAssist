package com.tyron.builder.compiler.incremental.java;

import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.java.JavaTask;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.Cache;

import org.apache.commons.io.FileUtils;
import org.openjdk.javax.tools.DiagnosticListener;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.StandardJavaFileManager;
import org.openjdk.javax.tools.StandardLocation;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.TaskEvent;
import org.openjdk.source.util.TaskListener;
import org.openjdk.tools.javac.api.JavacTool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class IncrementalJavaTask extends Task {

    private static final String TAG = IncrementalJavaTask.class.getSimpleName();

    private File mOutputDir;
    private List<File> mJavaFiles;
    private List<File> mFilesToCompile;
    private Cache<String, List<File>> mClassCache;
    private ILogger mLogger;

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mLogger = logger;

        mOutputDir = new File(project.getBuildDirectory(), "bin/classes");
        if (!mOutputDir.exists() && !mOutputDir.mkdirs()) {
            throw new IOException("Unable to create output directory");
        }

        project.clear();

        mFilesToCompile = new ArrayList<>();
        mClassCache = FileManager.getInstance().getClassCache();
        mJavaFiles = new ArrayList<>(project.getJavaFiles().values());
        mJavaFiles.addAll(JavaTask.getJavaFiles(new File(project.getBuildDirectory(), "gen")));

        for (Cache.Key<String> key : new HashSet<>(mClassCache.getKeys())) {
            for (File file : mClassCache.get(key.file, key.key)) {
                if (!mJavaFiles.contains(key.file.toFile())) {
                    if (file.delete()) {
                        Log.d(TAG, "Found deleted java file, removing " + key.file.toFile().getName() + " on the cache.");
                    }
                    mClassCache.remove(key.file, "class", "dex");
                }
            }
        }

        for (File file : mJavaFiles) {
            Path filePath = file.toPath();
            if (mClassCache.needs(filePath, "class")) {
                mFilesToCompile.add(file);
            }
        }

    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mFilesToCompile.isEmpty()) {
            return;
        }

        mLogger.debug("Compiling java files");

        DiagnosticListener<JavaFileObject> diagnosticCollector = diagnostic -> {
            switch (diagnostic.getKind()) {
                case ERROR:
                    mLogger.error(new DiagnosticWrapper(diagnostic));
                    break;
                case WARNING:
                    mLogger.warning(new DiagnosticWrapper(diagnostic));
            }
        };

        JavacTool tool = JavacTool.create();

        StandardJavaFileManager standardJavaFileManager = tool.getStandardFileManager(
                diagnosticCollector,
                Locale.getDefault(),
                Charset.defaultCharset()
        );

        List<File> classpath = new ArrayList<>(FileManager.getInstance().getLibraries());
        classpath.add(mOutputDir);

        try {
            standardJavaFileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(mOutputDir));
            standardJavaFileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, Arrays.asList(
                    FileManager.getInstance().getAndroidJar(),
                    FileManager.getInstance().getLambdaStubs()
            ));
            standardJavaFileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
            standardJavaFileManager.setLocation(StandardLocation.SOURCE_PATH, mJavaFiles);
        } catch (IOException e) {
            throw new CompilationFailedException(e);
        }

        List<JavaFileObject> javaFileObjects = new ArrayList<>();
        for (File file : mFilesToCompile) {
            javaFileObjects.add(new SourceFileObject(file.toPath()));
        }

        JavacTask task = tool.getTask(
                null,
                standardJavaFileManager,
                diagnosticCollector,
                null,
                null,
                javaFileObjects
        );

        try {
            task.parse();
            task.analyze();
            Iterable<? extends JavaFileObject> generate = task.generate();
            for (JavaFileObject fileObject : generate) {
                String path = fileObject.getName();
                File classFile = new File(path);
                if (classFile.exists()) {
                    Log.d(TAG, "Adding class file " + classFile.getName() + " to cache");
                    mClassCache.load(classFile.toPath(), "class", Collections.singletonList(classFile));
                }
            }
        } catch (Exception e) {
            throw new CompilationFailedException(e);
        }
    }

    @VisibleForTesting
    public List<File> getCompiledFiles() {
        return mFilesToCompile;
    }

    private File findClassFile(String packageName) {
        String path = packageName.replace(".", "/")
                .concat(".class");
        return new File(mOutputDir, path);
    }

    private void deleteAllFiles(File classFile, String ext) throws IOException {
        File parent = classFile.getParentFile();
        String name = classFile.getName().replace(ext, "");
        if (parent != null) {
            File[] children = parent.listFiles((c) -> c.getName().endsWith(ext) && c.getName().contains("$"));
            if (children != null) {
                for (File child : children) {
                    if (child.getName().startsWith(name)) {
                        FileUtils.delete(child);
                    }
                }
            }
        }
        FileUtils.delete(classFile);
    }
}
