package com.tyron.code.compiler.java;


import android.annotation.SuppressLint;
import android.util.Log;

import org.openjdk.source.util.JavacTask;
import org.openjdk.tools.javac.api.JavacTool;

import com.tyron.code.compiler.Task;
import com.tyron.code.completion.SourceFileObject;
import com.tyron.code.model.DiagnosticWrapper;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.service.ILogger;
import com.tyron.code.util.exception.CompilationFailedException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.DiagnosticListener;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.StandardJavaFileManager;
import org.openjdk.javax.tools.StandardLocation;

public class JavaTask extends Task {

    private ILogger logViewModel;
    private Project mProject;

    @Override
    public String getName() {
        return "Java Compiler";
    }

    @Override
    public void prepare(Project project, ILogger logger) throws IOException {
        mProject = project;
        logViewModel = logger;
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        compile();
    }

    private final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();

    @SuppressLint("NewApi")
    public void compile() throws CompilationFailedException {

        long startTime = System.currentTimeMillis();
        logViewModel.debug("Compiling java files.");

        File outputDir = new File(mProject.getBuildDirectory(), "bin/classes");
        if (outputDir.exists()) {
            FileManager.deleteDir(outputDir);
        }
        if(!outputDir.mkdirs()) {
            throw new CompilationFailedException("Cannot create output directory");
        }
        List<File> javaFiles = new ArrayList<>(FileManager.getInstance().getCurrentProject().javaFiles.values());
        javaFiles.addAll(getJavaFiles(new File(mProject.getBuildDirectory(), "gen")));

        DiagnosticListener<JavaFileObject> diagnosticCollector = diagnostic -> {
            switch (diagnostic.getKind()) {
                case ERROR:
                    logViewModel.error(new DiagnosticWrapper(diagnostic));
                    break;
                case WARNING:
                    logViewModel.warning(new DiagnosticWrapper(diagnostic));
            }
        };

        JavacTool tool = JavacTool.create();

        StandardJavaFileManager standardJavaFileManager = tool.getStandardFileManager(
                diagnosticCollector,
                Locale.getDefault(),
                Charset.defaultCharset()
        );
        try {
            standardJavaFileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(outputDir));
            standardJavaFileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, Arrays.asList(
                    FileManager.getInstance().getAndroidJar(),
                    FileManager.getInstance().getLambdaStubs()
            ));
            standardJavaFileManager.setLocation(StandardLocation.CLASS_PATH, FileManager.getInstance().getLibraries());
            standardJavaFileManager.setLocation(StandardLocation.SOURCE_PATH, javaFiles);
        } catch (IOException e) {
            throw new CompilationFailedException(e);
        }

        List<JavaFileObject> javaFileObjects = new ArrayList<>();
        for (File file : javaFiles) {
            javaFileObjects.add(new SourceFileObject(file.toPath()));
        }

        JavacTask task = tool.getTask(
                null,
                standardJavaFileManager,
                diagnosticCollector,
                List.of("-verbose"),
                null,
                javaFileObjects
        );

        if (!task.call()) {
            throw new CompilationFailedException("Compilation failed. Check diagnostics for more information.");
        }

        Log.d("JavaCompiler", "Compilation took: " + (System.currentTimeMillis() - startTime) + " ms");
    }

    private List<File> getJavaFiles(File dir) {
        List<File> javaFiles = new ArrayList<>();

        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }

        for (File file : files) {
            if (file.isDirectory()) {
                javaFiles.addAll(getJavaFiles(file));
            } else {
                if (file.getName().endsWith(".java")) {
                    javaFiles.add(file);
                }
            }
        }

        return javaFiles;
    }

    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
        return diagnostics;
    }
}
