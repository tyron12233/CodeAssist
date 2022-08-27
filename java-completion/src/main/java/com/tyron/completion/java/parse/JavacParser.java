package com.tyron.completion.java.parse;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import com.tyron.builder.BuildModule;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.compiler.services.NBAttr;
import com.tyron.completion.java.compiler.services.NBCheck;
import com.tyron.completion.java.compiler.services.NBClassFinder;
import com.tyron.completion.java.compiler.services.NBClassReader;
import com.tyron.completion.java.compiler.services.NBClassWriter;
import com.tyron.completion.java.compiler.services.NBEnter;
import com.tyron.completion.java.compiler.services.NBJavacTrees;
import com.tyron.completion.java.compiler.services.NBLog;
import com.tyron.completion.java.compiler.services.NBMemberEnter;
import com.tyron.completion.java.compiler.services.NBParserFactory;
import com.tyron.completion.java.compiler.services.NBResolve;
import com.tyron.completion.java.compiler.services.NBTreeMaker;

import org.apache.commons.io.output.NullWriter;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;

public class JavacParser {

    public void test() {

    }

    static JavacTaskImpl createJavacTask(
            final File file,
            final Iterable<? extends JavaFileObject> jfos,
            final File root,
            final List<File> cpInfo,
            final List<File> sourcePath,
            final JavacParser parser,
            final DiagnosticListener<? super JavaFileObject> diagnosticListener,
            final boolean detached) {
        Context context = new Context();
        NBLog.preRegister(context, new PrintWriter(new NullWriter()));

        List<String> options = new ArrayList<>();
        Collections.addAll(options, "-bootclasspath", joinPath(Arrays.asList(BuildModule.getAndroidJar(), BuildModule.getLambdaStubs())));
        Collections.addAll(options, "-target", "1.8", "-source", "1.8");
        Collections.addAll(options, "-cp", joinPath(cpInfo));

        JavacTool tool = JavacTool.create();
        JavacFileManager standardFileManager =
                tool.getStandardFileManager(diagnosticListener, Locale.getDefault(),
                        StandardCharsets.UTF_8);
        JavacTaskImpl task =
                (JavacTaskImpl) tool.getTask(null, standardFileManager, diagnosticListener, options,
                        Collections.singletonList("java.lang.Object"), jfos, context);

        NBClassReader.preRegister(context);
        NBAttr.preRegister(context);
        NBClassWriter.preRegister(context);
        NBClassFinder.preRegister(context);
        NBParserFactory.preRegister(context);
        NBTreeMaker.preRegister(context);
        NBJavacTrees.preRegister(context);
        NBResolve.preRegister(context);
        NBEnter.preRegister(context);
        NBMemberEnter.preRegister(context, true);
        NBCheck.preRegister(context);
        return task;
    }

    /**
     * Combine source path or class path entries using the system separator, for example ':' in unix
     */
    private static String joinPath(Collection<File> classOrSourcePath) {
        return classOrSourcePath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
    }
}
