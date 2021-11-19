package com.tyron.builder.compiler.incremental.dex;

import android.util.Log;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.OutputMode;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.dex.D8Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.common.util.Cache;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class IncrementalD8Task extends Task {

    private static final String TAG = IncrementalD8Task.class.getSimpleName();

    protected final DiagnosticsHandler diagnosticsHandler = new DiagnosticHandler();
    private List<Path> mClassFiles;
    private List<Path> mFilesToCompile;

    private Cache<String, List<File>> mDexCache;
    private ILogger mLogger;
    private Project mProject;
    private Path mOutputPath;

    private BuildType mBuildType;


    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mProject = project;
        mLogger = logger;
        mBuildType = type;
        mDexCache = mProject.getFileManager().getDexCache();

        File output = new File(project.getBuildDirectory(), "intermediate/classes");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Unable to create output directory");
        }
        mOutputPath = output.toPath();

        mFilesToCompile = new ArrayList<>();
        mClassFiles = new ArrayList<>(D8Task.getClassFiles(new File(project.getBuildDirectory(), "bin/java/classes")));
        mClassFiles.addAll(D8Task.getClassFiles(new File(project.getBuildDirectory(), "bin/kotlin/classes")));
        for (Cache.Key<String> key : new HashSet<>(mDexCache.getKeys())) {
            if (!mFilesToCompile.contains(key.file)) {
                File file = mDexCache.get(key.file, "dex").iterator().next();
                deleteAllFiles(file, ".dex");
                mDexCache.remove(key.file, "dex");
            }
        }

        for (Path file : mClassFiles) {
            if (mDexCache.needs(file, "dex")) {
                mFilesToCompile.add(file);
            }
        }
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mBuildType == BuildType.RELEASE) {
            doRelease();
        } else if (mBuildType == BuildType.DEBUG) {
            doDebug();
        }
    }

    private void doRelease() throws CompilationFailedException {
        try {
            ensureDexedLibraries();
            D8Command command = D8Command.builder(diagnosticsHandler)
                    .addClasspathFiles(mProject.getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
                    .addProgramFiles(mFilesToCompile)
                    .addLibraryFiles(getLibraryFiles())
                    .setMinApiLevel(mProject.getMinSdk())
                    .setMode(CompilationMode.RELEASE)
                    .setIntermediate(true)
                    .setOutput(mOutputPath, OutputMode.DexFilePerClassFile)
                    .build();
            D8.run(command);
            for (Path file : mFilesToCompile) {
                mDexCache.load(file, "dex", Collections.singletonList(getDexFile(file.toFile())));
            }

            mergeRelease();
        } catch (com.android.tools.r8.CompilationFailedException e) {
            throw new CompilationFailedException(e);
        }
    }

    private void doDebug() throws CompilationFailedException {
        try {
            ensureDexedLibraries();

            D8Command command = D8Command.builder(diagnosticsHandler)
                    .addClasspathFiles(mProject.getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
                    .addProgramFiles(mFilesToCompile)
                    .addLibraryFiles(getLibraryFiles())
                    .setMinApiLevel(mProject.getMinSdk())
                    .setMode(CompilationMode.DEBUG)
                    .setIntermediate(true)
                    .setOutput(mOutputPath, OutputMode.DexFilePerClassFile)
                    .build();
            D8.run(command);

            for (Path file : mFilesToCompile) {
                mDexCache.load(file, "dex", Collections.singletonList(getDexFile(file.toFile())));
            }

            D8Command.Builder builder = D8Command.builder(diagnosticsHandler)
                    .addProgramFiles(getAllDexFiles(mOutputPath.toFile()))
                    .addLibraryFiles(getLibraryFiles())
                    .addClasspathFiles(mProject.getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
                    .setMinApiLevel(mProject.getMinSdk());

            File output = new File(mProject.getBuildDirectory(), "bin");
            builder.setMode(CompilationMode.DEBUG);
            builder.setOutput(output.toPath(), OutputMode.DexIndexed);
            D8.run(builder.build());

        } catch (com.android.tools.r8.CompilationFailedException e) {
            throw new CompilationFailedException(e);
        }
    }

    private void mergeRelease() throws com.android.tools.r8.CompilationFailedException {
        mLogger.debug("Merging dex files using R8");

        File output = new File(mProject.getBuildDirectory(), "bin");
        D8Command command = D8Command.builder(diagnosticsHandler)
                .addClasspathFiles(mProject.getLibraries().stream().map(File::toPath)
                        .collect(Collectors.toList()))
                .addLibraryFiles(getLibraryFiles())
                .addProgramFiles(getAllDexFiles(mOutputPath.toFile()))
                .addProgramFiles(getLibraryDexes())
                .setMinApiLevel(mProject.getMinSdk())
                .setMode(CompilationMode.RELEASE)
                .setOutput(output.toPath(), OutputMode.DexIndexed)
                .build();
        D8.run(command);
    }

    private List<Path> getLibraryDexes() {
        List<Path> dexes = new ArrayList<>();
        for (File file : mProject.getLibraries()) {
            File parent = file.getParentFile();
            if (parent != null) {
                File[] dexFiles = parent.listFiles(file1 -> file1.getName().endsWith(".dex"));
                if (dexFiles != null) {
                    dexes.addAll(Arrays.stream(dexFiles).map(File::toPath)
                            .collect(Collectors.toList()));
                }
            }
        }
        return dexes;
    }


    private static final String INTERMEDIATE_DIR = "build/bin/classes/";

    private File getDexFile(File file) {
        File output = new File(mProject.getBuildDirectory(), "bin/classes/");
        String packageName = file.getAbsolutePath()
                .replace(output.getAbsolutePath(), "")
                .substring(1)
                .replace(".class", ".dex");

        File intermediate = new File(mProject.getBuildDirectory(), "intermediate/classes");
        File file1 = new File(intermediate, packageName);
        return file1;
    }

    /**
     * Ensures that all libraries of the project has been dex-ed
     *
     * @throws com.android.tools.r8.CompilationFailedException if the compilation has failed
     */
    protected void ensureDexedLibraries() throws com.android.tools.r8.CompilationFailedException {
        Set<File> libraries = mProject.getLibraries();

        for (File lib : libraries) {
            File parentFile = lib.getParentFile();
            if (parentFile == null) {
                continue;
            }
            File[] libFiles = lib.getParentFile().listFiles();
            if (libFiles == null) {
                if (!lib.delete()) {
                    mLogger.warning("Failed to delete " + lib.getAbsolutePath());
                }
            } else {
                File dex = new File(lib.getParentFile(), "classes.dex");
                if (dex.exists()) {
                    continue;
                }
                if (lib.exists()) {
                    mLogger.debug("Dexing jar " + parentFile.getName());
                    D8Command command = D8Command.builder(diagnosticsHandler)
                            .addLibraryFiles(getLibraryFiles())
                            .addClasspathFiles(libraries.stream().map(File::toPath)
                                    .collect(Collectors.toList()))
                            .setMinApiLevel(mProject.getMinSdk())
                            .addProgramFiles(lib.toPath())
                            .setMode(CompilationMode.RELEASE)
                            .setOutput(lib.getParentFile().toPath(), OutputMode.DexIndexed)
                            .build();
                    D8.run(command);
                }
            }
        }
    }

    private List<Path> getLibraryFiles() {
        List<Path> path = new ArrayList<>();
        path.add(FileManager.getAndroidJar().toPath());
        path.add(FileManager.getLambdaStubs().toPath());
        return path;
    }

    private void deleteAllFiles(File classFile, String ext) throws IOException {

        File dexFile = getDexFile(classFile);
        if (!dexFile.exists()) {
            return;
        }
        File parent = dexFile.getParentFile();
        String name = dexFile.getName().replace(ext, "");
        if (parent != null) {
            File[] children = parent.listFiles((c) -> c.getName().endsWith(ext) &&
                    c.getName().contains("$"));
            if (children != null) {
                for (File child : children) {
                    if (child.getName().startsWith(name)) {
                        FileUtils.delete(child);
                    }
                }
            }
        }
        FileUtils.delete(dexFile);
    }

    private List<Path> getAllDexFiles(File dir) {
        List<Path> files = new ArrayList<>();
        File[] children = dir.listFiles(c -> c.getName().endsWith(".dex") || c.isDirectory());
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    files.addAll(getAllDexFiles(child));
                } else {
                    files.add(child.toPath());
                }
            }
        }
        return files;
    }

    public class DiagnosticHandler implements DiagnosticsHandler {
        @Override
        public void error(Diagnostic diagnostic) {
            mLogger.error(wrap(diagnostic));
        }

        @Override
        public void warning(Diagnostic diagnostic) {
            mLogger.warning(wrap(diagnostic));
        }

        @Override
        public void info(Diagnostic diagnostic) {
            mLogger.info(wrap(diagnostic));
        }

        @Override
        public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel diagnosticsLevel,
                                                       Diagnostic diagnostic) {
            Log.d("DiagnosticHandler", diagnostic.getDiagnosticMessage());
            return null;
        }

        private DiagnosticWrapper wrap(Diagnostic diagnostic) {
            DiagnosticWrapper wrapper = new DiagnosticWrapper();
            wrapper.setMessage(diagnostic.getDiagnosticMessage());
            return wrapper;
        }
    }

}
