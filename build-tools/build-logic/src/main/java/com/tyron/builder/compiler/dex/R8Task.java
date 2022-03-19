package com.tyron.builder.compiler.dex;

import androidx.annotation.NonNull;

import com.android.tools.r8.ArchiveClassFileProvider;
import com.android.tools.r8.ClassFileResourceProvider;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.ProgramResource;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.inspector.Inspector;
import com.android.tools.r8.origin.Origin;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Library;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.util.AndroidUtilities;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class R8Task extends Task<AndroidModule> {

    private static final String TAG = R8Task.class.getSimpleName();

    public R8Task(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {

    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        try {
            ensureDexedLibraries();

            File output = new File(getModule().getBuildDirectory(), "bin");
            R8Command.Builder command = R8Command.builder(new DexDiagnosticHandler(getLogger(), getModule()))
                    .addLibraryFiles(getLibraryFiles())
                    .addProgramFiles(getDexFiles())
                    .addProgramFiles(D8Task.getClassFiles(new File(getModule().getBuildDirectory(),
                            "bin/kotlin/classes")))
                    .addProgramFiles(D8Task.getClassFiles(new File(getModule().getBuildDirectory(),
                            "bin/java/classes")))
                    .addProguardConfiguration(getDefaultProguardRule(), Origin.unknown())
                    .addProguardConfigurationFiles(getProguardRules())
                    .setMinApiLevel(getModule().getMinSdk())
                    .setMode(CompilationMode.RELEASE)
                    .setProguardMapOutputPath(output.toPath())
                    .setOutput(output.toPath(), OutputMode.DexIndexed);
            R8.run(command.build());
        } catch (com.android.tools.r8.CompilationFailedException e) {
            throw new CompilationFailedException(e);
        }
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
                    String message;
                    Library library = getModule().getLibrary(parentFile.getName());
                    if (library != null) {
                        boolean declared = library.getDeclaration() != null;
                        message = "Dexing library " +
                                  (declared ? library.getDeclaration() : library.getSourceFile()
                                          .getName());
                    } else {
                        message = "Dexing jar " + parentFile.getName();
                    }
                    getLogger().debug(message);
                    D8Command command = D8Command.builder(new DexDiagnosticHandler(getLogger(), getModule()))
                            .addLibraryFiles(getLibraryFiles())
                            .addClasspathFiles(libraries.stream().map(File::toPath)
                                                       .collect(Collectors.toList()))
                            .addProgramFiles(lib.toPath())
                            .setMode(CompilationMode.RELEASE)
                            .setMinApiLevel(getModule().getMinSdk())
                            .setOutput(lib.getParentFile().toPath(), OutputMode.DexIndexed)
                            .build();
                    D8.run(command);
                }
            }
        }
    }

    /**
     * Performs dexing on the given jar file.
     * Minification, desugaring and tree shaking are disabled, they will be performed
     * later during the dex merging process
     *
     * @param jarFile The jar file
     * @param outputDirectory The output directory where the files will be stored
     */
    private void dexLibrary(@NonNull File jarFile, @NonNull File outputDirectory) throws com.android.tools.r8.CompilationFailedException, IOException {
        if (!jarFile.exists()) {
            throw new IllegalArgumentException("Given file does not exist.");
        }
        if (!jarFile.getName().endsWith(".jar")) {
            throw new IllegalArgumentException("Given file is not a jar file: " + jarFile.getName());
        }

        if (!outputDirectory.exists()) {
            FileUtils.forceMkdir(outputDirectory);
        }

        D8Command.Builder command = D8Command.builder()
                .setMode(CompilationMode.RELEASE)
                .setOutput(outputDirectory.toPath(), OutputMode.DexIndexed);
        command.addLibraryFiles(getLibraryFiles());
        command.addClasspathFiles(getModule().getLibraries().stream()
                                          .map(File::toPath)
                                          .collect(Collectors.toList()));
        command.addProgramFiles(jarFile.toPath());
        command.setMinApiLevel(getModule().getMinSdk());
        D8.run(command.build());
    }

    private List<Path> getDexFiles() {
        List<File> libraries = getModule().getLibraries();
        return libraries.stream()
                .map(File::getParentFile)
                .filter(Objects::nonNull)
                .flatMap(it -> Arrays.stream(it.listFiles(c -> c.getName().endsWith(".dex"))))
                .map(File::toPath)
                .collect(Collectors.toList());
    }

    private List<String> getDefaultProguardRule() {
        List<String> rules = new ArrayList<>();
        // Keep resource classes
        rules.addAll(createConfiguration("-keepclassmembers class **.R$* {\n" +
                "    public static <fields>;\n" +
                "}"));
        rules.addAll(createConfiguration("-keepclassmembers class *" +
                " implements android.os.Parcelable {\n" +
                "  public static final android.os.Parcelable$Creator CREATOR;\n" +
                "}"));
        // For native methods, see http://proguard.sourceforge.net/manual/examples.html#native
        rules.addAll(createConfiguration("-keepclasseswithmembernames class * {\n" +
                "    native <methods>;\n" +
                "}"));
        rules.addAll(createConfiguration("-keepclassmembers enum * {\n" +
                "    public static **[] values();\n" +
                "    public static ** valueOf(java.lang.String);\n" +
                "}"));
        return rules;
    }

    private List<String> createConfiguration(String string) {
        return Arrays.asList(string.split("\n"));
    }

    private Collection<Path> getJarFiles() {
        Collection<Path> paths = new ArrayList<>();
        for (File it : getModule().getLibraries()) {
            paths.add(it.toPath());
        }
        return paths;
    }

    private List<Path> getProguardRules() {
        List<Path> rules = new ArrayList<>();
        getModule().getLibraries().forEach(it -> {
            File parent = it.getParentFile();
            if (parent != null) {
                File proguardFile = new File(parent, "proguard.txt");
                if (proguardFile.exists()) {
                    rules.add(proguardFile.toPath());
                }
            }
        });

        File proguardRuleTxt = new File(getModule().getRootFile(), "proguard-rules.pro");
        if (proguardRuleTxt.exists()) {
            rules.add(proguardRuleTxt.toPath());
        }

        File aapt2Rules = new File(getModule().getBuildDirectory(),
                "bin/res/generated-rules.txt");
        if (aapt2Rules.exists()) {
            rules.add(aapt2Rules.toPath());
        }
        return rules;
    }

    private List<Path> getLibraryFiles() {
        List<Path> path = new ArrayList<>();
        path.add(getModule().getBootstrapJarFile().toPath());
        path.add(getModule().getLambdaStubsJarFile().toPath());
        return path;
    }
}
