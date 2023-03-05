package org.gradle.process.internal;

import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.internal.jvm.JavaInfo;
import org.gradle.internal.jvm.Jvm;

import java.lang.management.ManagementFactory;

public class CurrentProcess {
    private final JavaInfo jvm;
    private final JvmOptions effectiveJvmOptions;

    public CurrentProcess(FileCollectionFactory fileCollectionFactory) {
        this(Jvm.current(), inferJvmOptions(fileCollectionFactory));
    }

    protected CurrentProcess(JavaInfo jvm, JvmOptions effectiveJvmOptions) {
        this.jvm = jvm;
        this.effectiveJvmOptions = effectiveJvmOptions;
    }

    public JvmOptions getJvmOptions() {
        return effectiveJvmOptions;
    }

    public JavaInfo getJvm() {
        return jvm;
    }

    private static JvmOptions inferJvmOptions(FileCollectionFactory fileCollectionFactory) {
        // Try to infer the effective jvm options for the currently running process.
        // We only care about 'managed' jvm args, anything else is unimportant to the running build
        JvmOptions jvmOptions = new JvmOptions(fileCollectionFactory);
        jvmOptions.setAllJvmArgs(ManagementFactory.getRuntimeMXBean().getInputArguments());
        return jvmOptions;
    }
}
