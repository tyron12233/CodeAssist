package org.gradle.api.internal.tasks.compile;

import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;

public class DefaultJavaCompileSpecFactory extends AbstractJavaCompileSpecFactory<DefaultJavaCompileSpec> {
    public DefaultJavaCompileSpecFactory(CompileOptions compileOptions, JavaInstallationMetadata toolchain) {
        super(compileOptions, toolchain);
    }

    @Override
    protected DefaultJavaCompileSpec getCommandLineSpec() {
        return new DefaultCommandLineJavaSpec();
    }

    @Override
    protected DefaultJavaCompileSpec getForkingSpec() {
        return new DefaultForkingJavaCompileSpec();
    }

    @Override
    protected DefaultJavaCompileSpec getDefaultSpec() {
        return new DefaultJavaCompileSpec();
    }

    private static class DefaultCommandLineJavaSpec extends DefaultJavaCompileSpec implements CommandLineJavaCompileSpec {
    }

    private static class DefaultForkingJavaCompileSpec extends DefaultJavaCompileSpec implements ForkingJavaCompileSpec {
    }
}
