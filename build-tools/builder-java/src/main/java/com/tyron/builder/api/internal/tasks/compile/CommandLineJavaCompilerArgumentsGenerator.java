package com.tyron.builder.api.internal.tasks.compile;

import com.google.common.collect.Iterables;
import com.tyron.builder.internal.process.ArgCollector;
import com.tyron.builder.internal.process.ArgWriter;

import java.io.File;
import java.io.Serializable;
import java.util.List;

public class CommandLineJavaCompilerArgumentsGenerator implements CompileSpecToArguments<JavaCompileSpec>, Serializable {
    @Override
    public void collectArguments(JavaCompileSpec spec, ArgCollector collector) {
        for (String arg : generate(spec)) {
            collector.args(arg);
        }
    }

    public Iterable<String> generate(JavaCompileSpec spec) {
        List<String> launcherOptions = new JavaCompilerArgumentsBuilder(spec).includeLauncherOptions(true).includeMainOptions(false).includeClasspath(false).build();
        List<String> remainingArgs = new JavaCompilerArgumentsBuilder(spec).includeSourceFiles(true).build();
        return Iterables.concat(launcherOptions, shortenArgs(spec.getTempDir(), remainingArgs));
    }

    private Iterable<String> shortenArgs(File tempDir, List<String> args) {
        // for command file format, see http://docs.oracle.com/javase/6/docs/technotes/tools/windows/javac.html#commandlineargfile
        // use platform character and line encoding
        return ArgWriter.argsFileGenerator(new File(tempDir, "java-compiler-args.txt"), ArgWriter.unixStyleFactory()).transform(args);
    }
}
