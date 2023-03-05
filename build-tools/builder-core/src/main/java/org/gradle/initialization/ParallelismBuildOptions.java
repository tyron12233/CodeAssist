package org.gradle.initialization;

import org.gradle.concurrent.ParallelismConfiguration;
import org.gradle.internal.buildoption.BooleanBuildOption;
import org.gradle.internal.buildoption.BooleanCommandLineOptionConfiguration;
import org.gradle.internal.buildoption.BuildOption;
import org.gradle.internal.buildoption.BuildOptionSet;
import org.gradle.internal.buildoption.CommandLineOptionConfiguration;
import org.gradle.internal.buildoption.Origin;
import org.gradle.internal.buildoption.StringBuildOption;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ParallelismBuildOptions extends BuildOptionSet<ParallelismConfiguration> {

    private static List<BuildOption<ParallelismConfiguration>> options;

    static {
        List<BuildOption<ParallelismConfiguration>> options = new ArrayList<BuildOption<ParallelismConfiguration>>();
        options.add(new ParallelOption());
        options.add(new MaxWorkersOption());
        ParallelismBuildOptions.options = Collections.unmodifiableList(options);
    }

    public static List<BuildOption<ParallelismConfiguration>> get() {
        return options;
    }

    @Override
    public List<? extends BuildOption<? super ParallelismConfiguration>> getAllOptions() {
        return options;
    }

    public static class ParallelOption extends BooleanBuildOption<ParallelismConfiguration> {
        public static final String GRADLE_PROPERTY = "org.gradle.parallel";

        public ParallelOption() {
            super(GRADLE_PROPERTY, BooleanCommandLineOptionConfiguration.create("parallel", "Build projects in parallel. Gradle will attempt to determine the optimal number of executor threads to use.", "Disables parallel execution to build projects."));
        }

        @Override
        public void applyTo(boolean value, ParallelismConfiguration settings, Origin origin) {
            settings.setParallelProjectExecutionEnabled(value);
        }
    }

    public static class MaxWorkersOption extends StringBuildOption<ParallelismConfiguration> {
        public static final String GRADLE_PROPERTY = "org.gradle.workers.max";
        public static final String LONG_OPTION = "max-workers";
        public static final String HINT = "must be a positive, non-zero, integer";

        public MaxWorkersOption() {
            super(GRADLE_PROPERTY, CommandLineOptionConfiguration.create(LONG_OPTION, "Configure the number of concurrent workers Gradle is allowed to use."));
        }

        @Override
        public void applyTo(String value, ParallelismConfiguration settings, Origin origin) {
            try {
                int workerCount = Integer.parseInt(value);
                if (workerCount < 1) {
                    origin.handleInvalidValue(value, HINT);
                }
                settings.setMaxWorkerCount(workerCount);
            } catch (NumberFormatException e) {
                origin.handleInvalidValue(value, HINT);
            }
        }
    }
}