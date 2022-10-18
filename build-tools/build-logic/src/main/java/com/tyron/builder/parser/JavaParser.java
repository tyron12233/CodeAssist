package com.tyron.builder.parser;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.tyron.builder.log.LogViewModel;

import com.sun.tools.javac.util.Context;

import java.io.File;
import java.util.Collections;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;


/**
 * use JavaCompilerService instead
 */
@Deprecated
public class JavaParser {

    public JavaParser(LogViewModel log) {

    }

    public CompilationUnitTree parse(File file, String src, int pos) {
        return null;
    }

    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
        return null;
    }

    public JavacTask getTask() {
        return null;
    }

    public Context getContext() {
        return null;
    }

    public List<String> packagePrivateTopLevelTypes(String packageName) {
        return Collections.emptyList();
    }

    public List<String> publicTopLevelTypes() {
        return null;
    }

    private List<File> classpath() {
        return null;
    }
}
