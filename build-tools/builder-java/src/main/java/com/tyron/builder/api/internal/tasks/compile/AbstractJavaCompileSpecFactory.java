package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.tasks.compile.CompileOptions;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.jvm.toolchain.JavaInstallationMetadata;

import javax.annotation.Nullable;

public abstract class AbstractJavaCompileSpecFactory<T extends JavaCompileSpec> implements Factory<T> {
    private final CompileOptions compileOptions;

    private final JavaInstallationMetadata toolchain;

    public AbstractJavaCompileSpecFactory(CompileOptions compileOptions, @Nullable JavaInstallationMetadata toolchain) {
        this.compileOptions = compileOptions;
        this.toolchain = toolchain;
    }

    public AbstractJavaCompileSpecFactory(CompileOptions compileOptions) {
        this(compileOptions, null);
    }

    @Override
    public T create() {
        if (toolchain != null) {
            return chooseSpecForToolchain();
        }
        if (compileOptions.isFork()) {
            if (compileOptions.getForkOptions().getExecutable() != null || compileOptions.getForkOptions().getJavaHome() != null) {
                return getCommandLineSpec();
            } else {
                return getForkingSpec();
            }
        } else {
            return getDefaultSpec();
        }
    }

    private T chooseSpecForToolchain() {
        if (!toolchain.getLanguageVersion().canCompileOrRun(8)) {
            return getCommandLineSpec();
        }
        if (compileOptions.isFork()) {
            return getForkingSpec();
        } else {
            if (isCurrentVmOurToolchain()) {
                return getDefaultSpec();
            }
            return getForkingSpec();
        }
    }

    boolean isCurrentVmOurToolchain() {
        return true;
//        return toolchain.getInstallationPath().getAsFile().equals(Jvm.current().getJavaHome());
    }

    abstract protected T getCommandLineSpec();

    abstract protected T getForkingSpec();

    abstract protected T getDefaultSpec();
}
