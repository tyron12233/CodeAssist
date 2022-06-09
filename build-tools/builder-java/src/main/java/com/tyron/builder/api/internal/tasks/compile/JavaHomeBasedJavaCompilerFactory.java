package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.jvm.Jvm;

import javax.tools.JavaCompiler;
import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JavaHomeBasedJavaCompilerFactory implements Factory<JavaCompiler>, Serializable {
    private final List<File> compilerPluginsClasspath;
    // We use a static cache here because we want to reuse classloaders in compiler workers as
    // it has a huge impact on performance. Previously there was a single, JdkTools.current()
    // instance, but we can have different "compiler plugins" classpath. For this reason we use
    // a map, but in practice it's likely there's only one instance in this map.
    private final static transient Map<List<File>, JdkTools> JDK_TOOLS = new ConcurrentHashMap<>();

    public JavaHomeBasedJavaCompilerFactory(List<File> compilerPluginsClasspath) {
        this.compilerPluginsClasspath = compilerPluginsClasspath;
    }

    @Override
    public JavaCompiler create() {
        JdkTools jdkTools = JavaHomeBasedJavaCompilerFactory.JDK_TOOLS.computeIfAbsent(compilerPluginsClasspath, JavaHomeBasedJavaCompilerFactory::createJdkTools);
        return jdkTools.getSystemJavaCompiler();
    }

    private static JdkTools createJdkTools(List<File> compilerPluginsClasspath) {
        return new JdkTools(Jvm.current(), compilerPluginsClasspath);
    }
}
