package org.gradle.launcher.daemon.configuration;

import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.internal.jvm.JavaInfo;
import org.gradle.process.internal.CurrentProcess;
import org.gradle.process.internal.JvmOptions;

import java.util.Properties;

public class BuildProcess extends CurrentProcess {

    public BuildProcess(FileCollectionFactory fileCollectionFactory) {
        super(fileCollectionFactory);
    }

    protected BuildProcess(JavaInfo jvm, JvmOptions effectiveJvmOptions) {
        super(jvm, effectiveJvmOptions);
    }

    /**
     * Attempts to configure the current process to run with the required build parameters.
     *
     * @return True if the current process could be configured, false otherwise.
     */
    public boolean configureForBuild(DaemonParameters requiredBuildParameters) {
        boolean javaHomeMatch = getJvm().equals(requiredBuildParameters.getEffectiveJvm());

        boolean immutableJvmArgsMatch = true;
        if (requiredBuildParameters.hasUserDefinedImmutableJvmArgs()) {
            immutableJvmArgsMatch = getJvmOptions().getAllImmutableJvmArgs().equals(requiredBuildParameters.getEffectiveSingleUseJvmArgs());
        }
        if (javaHomeMatch && immutableJvmArgsMatch && !isLowDefaultMemory(requiredBuildParameters)) {
            // Set the system properties and use this process
            Properties properties = new Properties();
            properties.putAll(requiredBuildParameters.getEffectiveSystemProperties());
            System.setProperties(properties);
            return true;
        }
        return false;
    }

    /**
     * Checks whether the current process is using the default client VM setting of 64m, which is too low to run the majority of builds.
     */
    private boolean isLowDefaultMemory(DaemonParameters daemonParameters) {
        if (daemonParameters.hasUserDefinedImmutableJvmArgs()) {
            for (String arg : daemonParameters.getEffectiveSingleUseJvmArgs()) {
                if (arg.startsWith("-Xmx")) {
                    return false;
                }
            }
        }
        return "64m".equals(getJvmOptions().getMaxHeapSize());
    }
}
