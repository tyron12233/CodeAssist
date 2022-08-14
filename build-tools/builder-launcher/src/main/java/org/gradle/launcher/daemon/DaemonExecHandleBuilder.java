package org.gradle.launcher.daemon;

import org.gradle.launcher.daemon.bootstrap.DaemonOutputConsumer;
import org.gradle.process.internal.ExecHandle;
import org.gradle.process.internal.ExecHandleBuilder;

import java.io.File;
import java.io.InputStream;
import java.util.List;

public class DaemonExecHandleBuilder {
    public ExecHandle build(List<String> args, File workingDir, DaemonOutputConsumer outputConsumer, InputStream inputStream, ExecHandleBuilder builder) {
        builder.commandLine(args);
        builder.setWorkingDir(workingDir);
        builder.setStandardInput(inputStream);
        builder.redirectErrorStream();
        builder.setTimeout(30000);
        builder.setDaemon(true);
        builder.setDisplayName("Gradle build daemon");
        builder.streamsHandler(outputConsumer);
        return builder.build();
    }
}
