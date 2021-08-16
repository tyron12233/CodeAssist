package com.tyron.code;
import com.sun.source.util.JavacTask;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import java.util.ServiceLoader;
import java.util.List;
import javax.tools.StandardJavaFileManager;
import java.util.Collections;
public class Parser {
    
    private static final JavaCompiler COMPILER = ServiceLoader.load(JavaCompiler.class).iterator().next();
    public static JavacTask createSingleFileTask(JavaFileObject file, StandardJavaFileManager fileManager) {
        return (JavacTask) COMPILER.getTask(
                null,
                fileManager,
                null,
                null,
                null,
                Collections.singleton(file));
    }
}
