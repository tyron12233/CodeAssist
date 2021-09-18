package com.tyron.builder.compiler.incremental.kotlin;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.BuildModule;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public class IncrementalKotlinCompiler extends Task {

    private static final String TAG = IncrementalKotlinCompiler.class.getSimpleName();

    private File mKotlinHome;
    private File mClassOutput;
    private Project mProject;
    private List<File> mFilesToCompile;
    private ILogger mLogger;

    private final MessageCollector mCollector = new Collector();

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(Project project, ILogger logger) throws IOException {
        mProject = project;
        mLogger = logger;
        mFilesToCompile = new ArrayList<>(getSourceFiles(project.getJavaDirectory()));

        mKotlinHome = new File(BuildModule.getContext().getFilesDir(), "kotlin-home");
        if (!mKotlinHome.exists() && !mKotlinHome.mkdirs()) {
            throw new IOException("Unable to create kotlin home directory");
        }

        mClassOutput = new File(project.getBuildDirectory(), "bin/classes");
        if (!mClassOutput.exists() && !mClassOutput.mkdirs()) {
            throw new IOException("Unable to create class output directory");
        }
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (mFilesToCompile.isEmpty()) {
            mLogger.info("No kotlin source files, Skipping compilation.");
            return;
        }
        List<File> classpath = new ArrayList<>();
        classpath.add(FileManager.getInstance().getAndroidJar());
        classpath.add(FileManager.getInstance().getLambdaStubs());
        classpath.addAll(mProject.getRJavaFiles().values());
        classpath.addAll(mProject.getLibraries());

        List<String> arguments = new ArrayList<>();
        Collections.addAll(arguments, "-cp", classpath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator)));
        Collections.addAll(arguments, mFilesToCompile.stream().map(File::getAbsolutePath).toArray(String[]::new));

        try {
            K2JVMCompiler compiler = new K2JVMCompiler();
            K2JVMCompilerArguments args = new K2JVMCompilerArguments();
            compiler.parseArguments(arguments.toArray(new String[0]), args);

            args.setCompileJava(false);
            args.setIncludeRuntime(false);
            args.setNoJdk(true);
            args.setNoReflect(true);
            args.setNoStdlib(true);
            args.setKotlinHome(mKotlinHome.getAbsolutePath());
            args.setDestination(mClassOutput.getAbsolutePath());
            compiler.exec(mCollector, Services.EMPTY, args);
        } catch (Exception e) {
            throw new CompilationFailedException(e);
        }

        if (mCollector.hasErrors()) {
            throw new CompilationFailedException("Compilation failed, see logs for more details");
        }
    }

    private static class Diagnostic extends DiagnosticWrapper {
        private final CompilerMessageSeverity mSeverity;
        private final String mMessage;
        private final CompilerMessageSourceLocation mLocation;

        public Diagnostic(CompilerMessageSeverity severity, String message, CompilerMessageSourceLocation location) {
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
                case ERROR: return Kind.ERROR;
                case STRONG_WARNING: return Kind.MANDATORY_WARNING;
                case WARNING: return Kind.WARNING;
                case LOGGING: return Kind.OTHER;
                default:
                case INFO: return Kind.NOTE;
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

        @Override
        public void clear() {
            mDiagnostics.clear();
        }

        @Override
        public boolean hasErrors() {
            Optional<Diagnostic> first = mDiagnostics.stream()
                    .filter(d -> d.mSeverity == CompilerMessageSeverity.ERROR)
                    .findFirst();
            return first.isPresent();
        }

        @Override
        public void report(@NotNull CompilerMessageSeverity severity, @NotNull String s, CompilerMessageSourceLocation compilerMessageSourceLocation) {
            Diagnostic diagnostic = new Diagnostic(severity, s, compilerMessageSourceLocation);
            mDiagnostics.add(diagnostic);

            switch (severity) {
                case ERROR: mLogger.error(diagnostic); break;
                case STRONG_WARNING:
                case WARNING: mLogger.warning(diagnostic); break;
                case INFO: mLogger.info(diagnostic); break;
                default: mLogger.debug(diagnostic);
            }
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
}
