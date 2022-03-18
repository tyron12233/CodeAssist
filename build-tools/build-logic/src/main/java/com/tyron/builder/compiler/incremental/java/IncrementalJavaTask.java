package com.tyron.builder.compiler.incremental.java;

import androidx.annotation.VisibleForTesting;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.incremental.kotlin.IncrementalKotlinCompiler;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.cache.CacheHolder;
import com.tyron.common.util.Cache;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;

public class IncrementalJavaTask extends Task<JavaModule> {

    public static final CacheHolder.CacheKey<String, List<File>> CACHE_KEY =
            new CacheHolder.CacheKey<>("javaCache");
    private static final String TAG = IncrementalJavaTask.class.getSimpleName();

    private File mOutputDir;
    private List<File> mJavaFiles;
    private List<File> mFilesToCompile;
    private Cache<String, List<File>> mClassCache;

    public IncrementalJavaTask(Project project, JavaModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mOutputDir = new File(getModule().getBuildDirectory(), "bin/java/classes");
        if (!mOutputDir.exists() && !mOutputDir.mkdirs()) {
            throw new IOException("Unable to create output directory");
        }

        mFilesToCompile = new ArrayList<>();
        mClassCache = getModule().getCache(CACHE_KEY, new Cache<>());

        mJavaFiles = new ArrayList<>(getModule().getJavaFiles().values());
        if (getModule() instanceof AndroidModule) {
            mJavaFiles.addAll(((AndroidModule) getModule()).getResourceClasses().values());
        }
        for (Cache.Key<String> key : new HashSet<>(mClassCache.getKeys())) {
            if (!mJavaFiles.contains(key.file.toFile())) {
                File file = mClassCache.get(key.file, "class").iterator().next();
                deleteAllFiles(file, ".class");
                mClassCache.remove(key.file, "class", "dex");
            }
        }

        for (File file : mJavaFiles) {
            Path filePath = file.toPath();
            if (mClassCache.needs(filePath, "class")) {
                mFilesToCompile.add(file);
            }
        }

    }

    private boolean mHasErrors = false;

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mFilesToCompile.isEmpty()) {
            return;
        }

        getLogger().debug("Compiling java files");

        DiagnosticListener<JavaFileObject> diagnosticCollector = diagnostic -> {
            switch (diagnostic.getKind()) {
                case ERROR:
                    mHasErrors = true;
                    getLogger().error(new DiagnosticWrapper(diagnostic));
                    break;
                case WARNING:
                    getLogger().warning(new DiagnosticWrapper(diagnostic));
            }
        };

        JavacTool tool = JavacTool.create();

        JavacFileManager standardJavaFileManager =
                tool.getStandardFileManager(diagnosticCollector, Locale.getDefault(),
                        Charset.defaultCharset());
        standardJavaFileManager.setSymbolFileEnabled(false);

        List<File> classpath = new ArrayList<>(getModule().getLibraries());
        classpath.add(mOutputDir);

        File kotlinOutputDir = new File(getModule().getBuildDirectory(), "bin/kotlin/classes");
        classpath.add(kotlinOutputDir);

        try {
            standardJavaFileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                    Collections.singletonList(mOutputDir));
            standardJavaFileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH,
                    Arrays.asList(getModule().getBootstrapJarFile(),
                            getModule().getLambdaStubsJarFile()));
            standardJavaFileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
            standardJavaFileManager.setLocation(StandardLocation.SOURCE_PATH, mJavaFiles);
        } catch (IOException e) {
            throw new CompilationFailedException(e);
        }

        List<JavaFileObject> javaFileObjects = new ArrayList<>();
        for (File file : mFilesToCompile) {
            javaFileObjects.add(new SimpleJavaFileObject(file.toURI(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                    return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                }
            });
        }

        List<String> options = new ArrayList<>();
        options.add("-source");
        options.add("1.8");
        options.add("-target");
        options.add("1.8");
        JavacTask task = tool.getTask(null, standardJavaFileManager, diagnosticCollector,
                options, null, javaFileObjects);

        HashMap<String, List<File>> compiledFiles = new HashMap<>();
        try {

            task.parse();
            task.analyze();
            Iterable<? extends JavaFileObject> generate = task.generate();
            for (JavaFileObject fileObject : generate) {
                String path = fileObject.getName();
                File classFile = new File(path);
                if (classFile. exists()) {
                    String classPath = classFile.getAbsolutePath().replace("build/bin/classes/",
                            "src/main/java/").replace(".class", ".java");
                    if (classFile.getName().indexOf('$') != -1) {
                        classPath = classPath.substring(0, classPath.indexOf('$')) + ".java";
                    }
                    File file = new File(classPath);
                    if (!file.exists()) {
                        file = new File(classPath.replace("src/main/java", "build/gen"));
                    }

                    if (!compiledFiles.containsKey(file.getAbsolutePath())) {
                        ArrayList<File> list = new ArrayList<>();
                        list.add(classFile);
                        compiledFiles.put(file.getAbsolutePath(), list);
                    } else {
                        Objects.requireNonNull(compiledFiles.get(file.getAbsolutePath())).add(classFile);
                    }
                    mClassCache.load(file.toPath(), "class", Collections.singletonList(classFile));
                }
            }

            compiledFiles.forEach((key, values) -> {
                File sourceFile = new File(key);
                String name = sourceFile.getName().replace(".java", "");
                File first = values.iterator().next();
                File parent = first.getParentFile();
                if (parent != null) {
                    File[] children = parent.listFiles(c -> {
                        if (!c.getName().contains("$")) {
                            return false;
                        }
                        String baseClassName = c.getName().substring(0, c.getName().indexOf('$'));
                        return baseClassName.equals(name);
                    });
                    if (children != null) {
                        for (File file : children) {
                            if (!values.contains(file)) {
                                if (file.delete()) {
                                    getLogger().debug("Deleted file " + file.getAbsolutePath());
                                }
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            throw new CompilationFailedException(e);
        }

        if (mHasErrors) {
            throw new CompilationFailedException("Compilation failed, check logs for more details");
        }
    }

    @VisibleForTesting
    public List<File> getCompiledFiles() {
        return mFilesToCompile;
    }

    private File findClassFile(String packageName) {
        String path = packageName.replace(".", "/").concat(".class");
        return new File(mOutputDir, path);
    }

    private void deleteAllFiles(File classFile, String ext) throws IOException {
        File parent = classFile.getParentFile();
        String name = classFile.getName().replace(ext, "");
        if (parent != null) {
            File[] children =
                    parent.listFiles((c) -> c.getName().endsWith(ext) && c.getName().contains("$"));
            if (children != null) {
                for (File child : children) {
                    if (child.getName().startsWith(name)) {
                        FileUtils.delete(child);
                    }
                }
            }
        }
        if (classFile.exists()) {
            FileUtils.delete(classFile);
        }
    }
}
