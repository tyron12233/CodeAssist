package org.gradle.launcher.daemon.bootstrap;

import org.gradle.launcher.bootstrap.ProcessBootstrap;

public class GradleDaemon {
    public static void main(String[] args) {
        ProcessBootstrap.run("org.gradle.launcher.daemon.bootstrap.DaemonMain", args);
    }
}
