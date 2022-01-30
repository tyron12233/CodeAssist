package com.tyron.builder.parser;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.JavacTask;
import com.tyron.builder.log.LogViewModel;

import org.openjdk.tools.javac.util.Context;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;


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
