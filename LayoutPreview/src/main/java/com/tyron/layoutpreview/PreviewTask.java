package com.tyron.layoutpreview;

import android.content.Context;
import android.util.Log;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.tyron.builder.compiler.dex.D8Task;
import com.tyron.builder.compiler.java.JavaTask;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.parser.FileManager;

import org.openjdk.javax.tools.StandardJavaFileManager;
import org.openjdk.javax.tools.StandardLocation;
import org.openjdk.source.util.JavacTask;
import org.openjdk.tools.javac.api.JavacTool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class PreviewTask {

    private final File mApkRes;
    private final File mRClassDir;
    private final List<File> mLibraryDexes;
    private final List<File> mLibraryRes;

    private final File mTempDir;

    public PreviewTask(File apkRes, File rClassDir, List<File> libraryDexes, List<File> libraryRes, File tempDir) {
        mApkRes = apkRes;
        mRClassDir = rClassDir;
        mLibraryDexes = libraryDexes;
        mTempDir = tempDir;
        mLibraryRes = libraryRes;
    }

    public Optional<PreviewContext> run(Context context) {
        try {
            compileJava();
            File mainDex = runD8();

            PreviewClassLoader classLoader = PreviewClassLoader.newInstance(mLibraryDexes, mainDex);
            PreviewLoader loader = new PreviewLoader(context);
            loader.initialize(classLoader);
            loader.addAssetPath(mApkRes.getAbsolutePath());
            mLibraryRes.forEach(file -> loader.addAssetPath(file.getAbsolutePath()));

            PreviewContext previewContext = loader.getPreviewContext();
            previewContext.setClassLoader(classLoader);
            //previewContext.setTheme(loader.getResources().getIdentifier("Theme_MyApplication", "style", "androidx.test"));
            return Optional.of(previewContext);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private void compileJava() throws IOException  {
        JavacTool tool = JavacTool.create();
        Set<File> classFiles = JavaTask.getJavaFiles(mRClassDir);
        File output = new File(mTempDir, "classes");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Unable to create layout output directory");
        }

        StandardJavaFileManager fileManager = tool.getStandardFileManager(null, Locale.getDefault(), Charset.defaultCharset());
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(output));
        fileManager.setLocation(StandardLocation.CLASS_PATH, classFiles);
        fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, Arrays.asList(FileManager.getInstance().getAndroidJar(), FileManager.getInstance().getLambdaStubs()));


        JavacTask task = tool
                .getTask(null, fileManager, null, null, null, classFiles.stream()
                .map(file -> new SourceFileObject(file.toPath()))
                .collect(Collectors.toList()));

        task.call();
    }

    private File runD8() throws IOException, CompilationFailedException {
        File output = new File(mTempDir, "output");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Unable to create output directory");
        }

        List<Path> paths = D8Task.getClassFiles(new File(mTempDir, "classes"));
        D8Command command = D8Command.builder()
                .setOutput(output.toPath(), OutputMode.DexIndexed)
                .addProgramFiles(paths)
                .build();
        D8.run(command);

        return new File(output, "classes.dex");
    }
}
