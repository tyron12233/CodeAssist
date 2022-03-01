package com.tyron.builder.compiler.dex;

import androidx.annotation.VisibleForTesting;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
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
import java.util.stream.Collectors;

/**
 * Task for dexing a {@link JavaModule} this is copied from {@link IncrementalD8Task}
 * but with the minimum API levels hardcoded to 21
 */
public class JavaD8Task extends Task<JavaModule> {

    public JavaD8Task(Project project, JavaModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return "JavaD8Task";
    }

    private DiagnosticsHandler diagnosticsHandler;
    private List<Path> mClassFiles;
    private List<Path> mFilesToCompile;

    private Cache<String, List<File>> mDexCache;
    private Path mOutputPath;

    private BuildType mBuildType;


    @Override
    public void prepare(BuildType type) throws IOException {
        mBuildType = type;
        diagnosticsHandler = new DexDiagnosticHandler(getLogger(), getModule());
        mDexCache = getModule().getCache(IncrementalD8Task.CACHE_KEY, new Cache<>());

        File output = new File(getModule().getBuildDirectory(), "intermediate/classes");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Unable to create output directory");
        }
        mOutputPath = output.toPath();

        mFilesToCompile = new ArrayList<>();
        mClassFiles = new ArrayList<>(D8Task.getClassFiles(new File(getModule().getBuildDirectory(), "bin/java/classes")));
        mClassFiles.addAll(D8Task.getClassFiles(new File(getModule().getBuildDirectory(), "bin/kotlin/classes")));
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
        if (mBuildType == BuildType.RELEASE || mBuildType == BuildType.AAB) {
            doRelease();
        } else if (mBuildType == BuildType.DEBUG) {
            doDebug();
        }
    }

    @Override
    protected void clean() {
        super.clean();
    }

    private void doRelease() throws CompilationFailedException {
        try {
            ensureDexedLibraries();
            D8Command command = D8Command.builder(diagnosticsHandler)
                    .addClasspathFiles(getModule().getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
                    .addProgramFiles(mFilesToCompile)
                    .addLibraryFiles(getLibraryFiles())
                    .setMinApiLevel(21)
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
                    .addClasspathFiles(getModule().getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
                    .addProgramFiles(mFilesToCompile)
                    .addLibraryFiles(getLibraryFiles())
                    .setMinApiLevel(21)
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
                    .addClasspathFiles(getModule().getLibraries().stream().map(File::toPath).collect(Collectors.toList()))
                    .setMinApiLevel(21);

            File output = new File(getModule().getBuildDirectory(), "bin");
            builder.setMode(CompilationMode.DEBUG);
            builder.setOutput(output.toPath(), OutputMode.DexIndexed);
            D8.run(builder.build());

        } catch (com.android.tools.r8.CompilationFailedException e) {
            throw new CompilationFailedException(e);
        }
    }

    private void mergeRelease() throws com.android.tools.r8.CompilationFailedException {
        getLogger().debug("Merging dex files using R8");

        File output = new File(getModule().getBuildDirectory(), "bin");
        D8Command command = D8Command.builder(diagnosticsHandler)
                .addClasspathFiles(getModule().getLibraries().stream().map(File::toPath)
                        .collect(Collectors.toList()))
                .addLibraryFiles(getLibraryFiles())
                .addProgramFiles(getAllDexFiles(mOutputPath.toFile()))
                .addProgramFiles(getLibraryDexes())
                .setMinApiLevel(21)
                .setMode(CompilationMode.RELEASE)
                .setOutput(output.toPath(), OutputMode.DexIndexed)
                .build();
        D8.run(command);
    }

    @VisibleForTesting
    public List<Path> getCompiledFiles() {
        return mFilesToCompile;
    }

    private List<Path> getLibraryDexes() {
        List<Path> dexes = new ArrayList<>();
        for (File file : getModule().getLibraries()) {
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


    private File getDexFile(File file) {
        File output = new File(getModule().getBuildDirectory(), "bin/classes/");
        String packageName = file.getAbsolutePath()
                .replace(output.getAbsolutePath(), "")
                .substring(1)
                .replace(".class", ".dex");

        File intermediate = new File(getModule().getBuildDirectory(), "intermediate/classes");
        File file1 = new File(intermediate, packageName);
        return file1;
    }

    /**
     * Ensures that all libraries of the project has been dex-ed
     *
     * @throws com.android.tools.r8.CompilationFailedException if the compilation has failed
     */
    protected void ensureDexedLibraries() throws com.android.tools.r8.CompilationFailedException {
        List<File> libraries = getModule().getLibraries();

        for (File lib : libraries) {
            File parentFile = lib.getParentFile();
            if (parentFile == null) {
                continue;
            }
            File[] libFiles = lib.getParentFile().listFiles();
            if (libFiles == null) {
                if (!lib.delete()) {
                    getLogger().warning("Failed to delete " + lib.getAbsolutePath());
                }
            } else {
                File dex = new File(lib.getParentFile(), "classes.dex");
                if (dex.exists()) {
                    continue;
                }
                if (lib.exists()) {
                    getLogger().debug("Dexing jar " + parentFile.getName());
                    D8Command command = D8Command.builder(diagnosticsHandler)
                            .addLibraryFiles(getLibraryFiles())
                            .addClasspathFiles(libraries.stream().map(File::toPath)
                                    .collect(Collectors.toList()))
                            .setMinApiLevel(21)
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
        path.add(getModule().getLambdaStubsJarFile().toPath());
        path.add(getModule().getBootstrapJarFile().toPath());
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

}
