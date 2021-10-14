package com.tyron.builder.compiler.log;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.parser.FileManager;

import org.junit.Test;
import org.openjdk.javax.tools.StandardJavaFileManager;
import org.openjdk.javax.tools.StandardLocation;
import org.openjdk.source.util.JavacTask;
import org.openjdk.tools.javac.api.JavacTool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Locale;

public class GenerateLog {

    private static final File LOGGER = new File("C:/Users/admin/StudioProjects/CodeAssist/build-logic/src/test/java/com/tyron/builder/compiler/log");

    @Test
    public void generateLogger() throws IOException {
        JavacTool tool = JavacTool.create();
        StandardJavaFileManager fileManager = tool.getStandardFileManager(null, Locale.getDefault(), Charset.defaultCharset());
        fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, Collections.singleton(FileManager.getInstance().getAndroidJar()));
        fileManager.setLocation(StandardLocation.CLASS_PATH, Collections.singletonList(LOGGER));
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(LOGGER.getParentFile()));
        JavacTask task = tool.getTask(null, fileManager, null, Collections.emptyList(), Collections.emptyList(), Collections.singletonList(new SourceFileObject(LOGGER.toPath())));
        task.call();

    }
}
