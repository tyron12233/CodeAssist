package com.tyron.code.compiler;


import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Log;
import com.tyron.code.SourceFileObject;
import com.tyron.code.editor.log.LogViewModel;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.util.exception.CompilationFailedException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public class JavaCompiler {

    private final LogViewModel logViewModel;
    private final Project mProject;

    public JavaCompiler(LogViewModel log, Project project) {
        logViewModel = log;
        mProject = project;
    }

    private final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();

    public void compile() throws CompilationFailedException {

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
                    logViewModel.e(LogViewModel.BUILD_LOG, diagnostic.toString());
                    break;
                case WARNING:
                    logViewModel.w(LogViewModel.BUILD_LOG, diagnostic.toString());
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
                null,
                null,
                javaFileObjects
        );

        if (!task.call()) {
            throw new CompilationFailedException("Compilation failed. Check diagnostics for more information.");
        }
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
