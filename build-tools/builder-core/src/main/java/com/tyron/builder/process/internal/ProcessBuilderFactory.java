package com.tyron.builder.process.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates a {@link java.lang.ProcessBuilder} based on a {@link ExecHandle}.
 */
public class ProcessBuilderFactory {
    public ProcessBuilder createProcessBuilder(ProcessSettings processSettings) {
        List<String> commandWithArguments = new ArrayList<String>();
        commandWithArguments.add(processSettings.getCommand());
        commandWithArguments.addAll(processSettings.getArguments());

        ProcessBuilder processBuilder = new ProcessBuilder(commandWithArguments);
        processBuilder.directory(processSettings.getDirectory());
        processBuilder.redirectErrorStream(processSettings.getRedirectErrorStream());

        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        environment.putAll(processSettings.getEnvironment());

        return processBuilder;
    }
}
