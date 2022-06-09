package com.tyron.builder.internal.jvm.inspection;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.internal.jvm.JavaInfo;
import com.tyron.builder.process.internal.ExecException;

import java.io.File;
import java.nio.file.NoSuchFileException;

public class DefaultJvmVersionDetector implements JvmVersionDetector {

    private final JvmMetadataDetector detector;

    public DefaultJvmVersionDetector(JvmMetadataDetector detector) {
        this.detector = detector;
    }

    @Override
    public JavaVersion getJavaVersion(JavaInfo jvm) {
        return getVersionFromJavaHome(jvm.getJavaHome());
    }

    @Override
    public JavaVersion getJavaVersion(String javaCommand) {
        File executable = new File(javaCommand);
        File parentFolder = executable.getParentFile();
        if(parentFolder == null || !parentFolder.exists()) {
            Exception cause = new NoSuchFileException(javaCommand);
            throw new ExecException("A problem occurred starting process 'command '" + javaCommand + "''", cause);
        }
        return getVersionFromJavaHome(parentFolder.getParentFile());
    }

    private JavaVersion getVersionFromJavaHome(File javaHome) {
        return validate(detector.getMetadata(javaHome)).getLanguageVersion();
    }

    private JvmInstallationMetadata validate(JvmInstallationMetadata metadata) {
        if(metadata.isValidInstallation()) {
            return metadata;
        }
        throw new BuildException("Unable to determine version for JDK located at " + metadata.getJavaHome() + ". Reason: " + metadata.getErrorMessage(), metadata.getErrorCause());
    }
}
