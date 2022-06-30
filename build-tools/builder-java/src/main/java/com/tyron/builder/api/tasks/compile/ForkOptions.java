package com.tyron.builder.api.tasks.compile;

import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.compile.ProviderAwareCompilerDaemonForkOptions;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Map;

/**
 * Fork options for Java compilation. Only take effect if {@code CompileOptions.fork} is {@code true}.
 */
public class ForkOptions extends ProviderAwareCompilerDaemonForkOptions {
    private static final long serialVersionUID = 0;

    private String executable;

    private String tempDir;

    private File javaHome;

    /**
     * Returns the compiler executable to be used. If set,
     * a new compiler process will be forked for every compile task.
     * Defaults to {@code null}.
     *
     * <p>Setting the executable disables task output caching.</p>
     */
    @Nullable
    @Optional
    @Input
    public String getExecutable() {
        return executable;
    }

    /**
     * Sets the compiler executable to be used. If set,
     * a new compiler process will be forked for every compile task.
     * Defaults to {@code null}.
     *
     * <p>Setting the executable disables task output caching.</p>
     */
    public void setExecutable(@Nullable String executable) {
        this.executable = executable;
    }

    /**
     * Returns the Java home which contains the compiler to use.
     * If set, a new compiler process will be forked for every compile task.
     * Defaults to {@code null}.
     *
     * @since 3.5
     */
    @Internal
    @Nullable
    public File getJavaHome() {
        return javaHome;
    }

    /**
     * Sets the Java home which contains the compiler to use.
     * If set, a new compiler process will be forked for every compile task.
     * Defaults to {@code null}.
     *
     * @since 3.5
     */
    public void setJavaHome(@Nullable File javaHome) {
        this.javaHome = javaHome;
    }

    /**
     * Returns the directory used for temporary files that may be created to pass
     * command line arguments to the compiler process. Defaults to {@code null},
     * in which case the directory will be chosen automatically.
     */
    @Internal
    @Nullable
    public String getTempDir() {
        return tempDir;
    }

    /**
     * Sets the directory used for temporary files that may be created to pass
     * command line arguments to the compiler process. Defaults to {@code null},
     * in which case the directory will be chosen automatically.
     */
    public void setTempDir(@Nullable String tempDir) {
        this.tempDir = tempDir;
    }

    public void define(Map<String, Object> forkArgs) {

    }
}