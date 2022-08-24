package org.gradle.launcher.daemon.bootstrap;

import org.gradle.launcher.bootstrap.ProcessBootstrap;

public class GradleDaemon {
    public static void main(String[] args) {
        System.out.println("DAEMON STARTED");
        ProcessBootstrap.run("org.gradle.launcher.daemon.bootstrap.DaemonMain", args);
    }
}
