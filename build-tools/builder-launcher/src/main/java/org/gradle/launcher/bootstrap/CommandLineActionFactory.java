package org.gradle.launcher.bootstrap;

import java.util.List;

public interface CommandLineActionFactory {
    CommandLineExecution convert(List<String> args);

    interface CommandLineExecution {
        void execute(ExecutionListener listener);
    }
}
