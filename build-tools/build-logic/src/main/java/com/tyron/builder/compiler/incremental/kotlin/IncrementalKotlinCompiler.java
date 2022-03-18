package com.tyron.builder.compiler.incremental.kotlin;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.common.base.Throwables;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;

import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.build.report.ICReporterBase;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunnerKt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import dalvik.system.DexFile;
import kotlin.jvm.functions.Function0;

public class IncrementalKotlinCompiler extends Task<AndroidModule> {

    private static final String TAG = IncrementalKotlinCompiler.class.getSimpleName();

    private File mKotlinHome;
    private File mClassOutput;
    private List<File> mFilesToCompile;

    private final MessageCollector mCollector = new Collector();

    public IncrementalKotlinCompiler(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mFilesToCompile = new ArrayList<>();
        mFilesToCompile.addAll(getModule().getJavaFiles().values());
        mFilesToCompile.addAll(getModule().getKotlinFiles().values());

//        mKotlinHome = new File(BuildModule.getContext().getFilesDir(), "kotlin-home");
//        if (!mKotlinHome.exists() && !mKotlinHome.mkdirs()) {
//            throw new IOException("Unable to create kotlin home directory");
//        }

        mClassOutput = new File(getModule().getBuildDirectory(), "bin/kotlin/classes");
        if (!mClassOutput.exists() && !mClassOutput.mkdirs()) {
            throw new IOException("Unable to create class output directory");
        }
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mFilesToCompile.stream().noneMatch(file -> file.getName().endsWith(".kt"))) {
            getLogger().info("No kotlin source files, Skipping compilation.");
            return;
        }
        List<File> classpath = new ArrayList<>();
        classpath.add(getModule().getBootstrapJarFile());
        classpath.add(getModule().getLambdaStubsJarFile());
        classpath.addAll(getModule().getLibraries());
        List<String> arguments = new ArrayList<>();
        Collections.addAll(arguments, "-cp",
                classpath.stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator)));

        List<File> javaSourceRoots = new ArrayList<>(getModule().getJavaFiles().values());

        try {
            K2JVMCompiler compiler = new K2JVMCompiler();
            K2JVMCompilerArguments args = new K2JVMCompilerArguments();
            compiler.parseArguments(arguments.toArray(new String[0]), args);

            args.setUseJavac(false);
            args.setCompileJava(false);
            args.setIncludeRuntime(false);
            args.setNoJdk(true);
            args.setModuleName("codeassist-kotlin");
            args.setNoReflect(true);
            args.setNoStdlib(true);
            args.setSuppressWarnings(false);
            args.setJavaSourceRoots(javaSourceRoots.stream()
                    .map(File::getAbsolutePath)
                    .toArray(String[]::new));
           // args.setKotlinHome(mKotlinHome.getAbsolutePath());
            args.setDestination(mClassOutput.getAbsolutePath());

            List<File> plugins = getPlugins();
            getLogger().debug("Loading kotlin compiler plugins: " + plugins);

            args.setPluginClasspaths(plugins.stream()
                    .map(File::getAbsolutePath)
                    .toArray(String[]::new));
            args.setPluginOptions(getPluginOptions());

            File cacheDir = new File(getModule().getBuildDirectory(), "intermediate/kotlin");

            IncrementalJvmCompilerRunnerKt.makeIncrementally(cacheDir,
                    Arrays.asList(getModule().getJavaDirectory(),
                            new File(getModule().getBuildDirectory(), "gen")),
                    args, mCollector, new ICReporterBase() {
                        @Override
                        public void report(@NonNull Function0<String> function0) {
                            getLogger().info(function0.invoke());
                        }

                        @Override
                        public void reportVerbose(@NonNull Function0<String> function0) {
                            getLogger().verbose(function0.invoke());
                        }

                        @Override
                        public void reportCompileIteration(boolean incremental,
                                                           @NonNull Collection<? extends File> sources,
                                                           @NonNull ExitCode exitCode) {
                        }
                    });
        } catch (Exception e) {
            throw new CompilationFailedException(Throwables.getStackTraceAsString(e));
        }

        if (mCollector.hasErrors()) {
            throw new CompilationFailedException("Compilation failed, see logs for more details");
        }
    }

    private List<File> getSourceFiles(File dir) {
        List<File> files = new ArrayList<>();

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    files.addAll(getSourceFiles(child));
                } else {
                    if (child.getName().endsWith(".kt") || child.getName().endsWith(".java")) {
                        files.add(child);
                    }
                }
            }
        }

        return files;
    }

    private List<File> getPlugins() {
        File pluginDir = new File(getModule().getBuildDirectory(), "plugins");
        File[] children = pluginDir.listFiles(c -> c.getName().endsWith(".jar"));

        if (children == null) {
            return Collections.emptyList();
        }

        return Arrays.stream(children)
                .collect(Collectors.toList());
    }

    private String[] getPluginOptions() throws IOException {
        File pluginDir = new File(getModule().getBuildDirectory(), "plugins");
        File args = new File(pluginDir, "args.txt");
        if (!args.exists()) {
            return new String[0];
        }

        String string = FileUtils.readFileToString(args, StandardCharsets.UTF_8);
        return string.split(" ");
    }

    private static class Diagnostic extends DiagnosticWrapper {
        private final CompilerMessageSeverity mSeverity;
        private final String mMessage;
        private final CompilerMessageSourceLocation mLocation;

        public Diagnostic(CompilerMessageSeverity severity,
                          String message, CompilerMessageSourceLocation location) {
            mSeverity = severity;
            mMessage = message;

            if (location == null) {
                mLocation = new CompilerMessageSourceLocation() {
                    @NonNull
                    @Override
                    public String getPath() {
                        return "UNKNOWN";
                    }

                    @Override
                    public int getLine() {
                        return 0;
                    }

                    @Override
                    public int getColumn() {
                        return 0;
                    }

                    @Override
                    public int getLineEnd() {
                        return 0;
                    }

                    @Override
                    public int getColumnEnd() {
                        return 0;
                    }

                    @Override
                    public String getLineContent() {
                        return "";
                    }
                };
            } else {
                mLocation = location;
            }
        }

        @Override
        public File getSource() {
            if (mLocation == null || TextUtils.isEmpty(mLocation.getPath())) {
                return new File("UNKNOWN");
            }
            return new File(mLocation.getPath());
        }

        @Override
        public Kind getKind() {
            switch (mSeverity) {
                case ERROR:
                    return Kind.ERROR;
                case STRONG_WARNING:
                    return Kind.MANDATORY_WARNING;
                case WARNING:
                    return Kind.WARNING;
                case LOGGING:
                    return Kind.OTHER;
                default:
                case INFO:
                    return Kind.NOTE;
            }
        }

        @Override
        public long getLineNumber() {
            return mLocation.getLine();
        }

        @Override
        public long getColumnNumber() {
            return mLocation.getColumn();
        }

        @Override
        public String getMessage(Locale locale) {
            return mMessage;
        }
    }

    private class Collector implements MessageCollector {

        private final List<Diagnostic> mDiagnostics = new ArrayList<>();
        private boolean mHasErrors;
        
        @Override
        public void clear() {
            mDiagnostics.clear();
        }

        @Override
        public boolean hasErrors() {
            return mHasErrors;
        }

        @Override
        public void report(@NotNull CompilerMessageSeverity severity,
                           @NotNull String message,
                           CompilerMessageSourceLocation location) {
            if (message.contains("No class roots are found in the JDK path"))  {
                // Android does not have JDK so its okay to ignore this error
                return;
            }
            Diagnostic diagnostic = new Diagnostic(severity, message, location);
            mDiagnostics.add(diagnostic);

            switch (severity) {
                case ERROR:
                    mHasErrors = true;
                    getLogger().error(diagnostic);
                    break;
                case STRONG_WARNING:
                case WARNING:
                    getLogger().warning(diagnostic);
                    break;
                case INFO:
                    getLogger().info(diagnostic);
                    break;
                default:
                    getLogger().debug(diagnostic);
            }
        }
    }
}
