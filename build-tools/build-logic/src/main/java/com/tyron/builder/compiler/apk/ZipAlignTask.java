package com.tyron.builder.compiler.apk;

import android.content.Context;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.BinaryExecutor;

import javax.tools.Diagnostic;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ZipAlignTask extends Task<AndroidModule> {

    private static final String TAG = ZipAlignTask.class.getSimpleName();

    private File mApkFile;

    public ZipAlignTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mApkFile = new File(getModule().getBuildDirectory(), "bin/generated.apk");

        if (!mApkFile.exists()) {
            throw new IOException("Unable to find signed apk file in projects build path");
        }
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        File binary = getZipAlignBinary();
        List<String> args = new ArrayList<>();
        args.add(binary.getAbsolutePath());
        args.add("-f");
        args.add("4");
        args.add(mApkFile.getAbsolutePath());
        args.add(mApkFile.getParent() + "/aligned.apk");
        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(args);

        List<DiagnosticWrapper> wrappers = new ArrayList<>();
        String logs = executor.execute();
        if (!logs.isEmpty()) {
            String[] lines = logs.split("\n");
            if (lines.length == 0) {
                lines = new String[]{logs};
            }

            for (String line : lines) {
                Diagnostic.Kind kind = getKind(line);
                if (line.contains(":")) {
                    line = line.substring(line.indexOf(':'));
                }

                DiagnosticWrapper wrapper = new DiagnosticWrapper();
                wrapper.setLineNumber(-1);
                wrapper.setColumnNumber(-1);
                wrapper.setStartPosition(-1);
                wrapper.setEndPosition(-1);
                wrapper.setKind(kind);
                wrapper.setMessage(line);
                wrappers.add(wrapper);
            }
        }

        boolean hasErrors = wrappers.stream()
                .anyMatch(it -> it.getKind() == Diagnostic.Kind.ERROR);
        if (hasErrors) {
            throw new CompilationFailedException("Check logs for more details.");
        }
    }

    private Diagnostic.Kind getKind(String string) {
        String trimmed = string.trim();
        if (trimmed.startsWith("WARNING")) {
            return Diagnostic.Kind.WARNING;
        } else if (trimmed.startsWith("ERROR")) {
            return Diagnostic.Kind.ERROR;
        } else {
            return Diagnostic.Kind.NOTE;
        }
    }

    private File getZipAlignBinary() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        String path = context.getApplicationInfo().nativeLibraryDir + "/libzipalign.so";
        File file = new File(path);
        if (!file.exists()) {
            throw new IOException("ZipAlign binary not found.");
        }
        return file;
    }
}
