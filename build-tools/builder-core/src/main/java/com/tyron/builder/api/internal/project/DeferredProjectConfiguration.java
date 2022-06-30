package com.tyron.builder.api.internal.project;

import com.google.common.collect.Lists;
import com.tyron.builder.api.BuildProject;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.List;

@NotThreadSafe
public class DeferredProjectConfiguration {

    private final static String TRACE = "org.gradle.trace.deferred.project.configuration";

    private final BuildProject project;
    private final List<Runnable> configuration = Lists.newLinkedList();
    private boolean fired;

    private Throwable firedSentinel;

    public DeferredProjectConfiguration(BuildProject project) {
        this.project = project;
    }

    public void add(Runnable configuration) {
        if (fired) {
            String message = "Cannot add deferred configuration for project " + project.getPath();
            if (firedSentinel == null) {
                throw new IllegalStateException(message);
            } else {
                throw new IllegalStateException(message, firedSentinel);
            }
        } else {
            this.configuration.add(configuration);
        }
    }

    public void fire() {
        if (!fired) {
            if (Boolean.getBoolean(TRACE)) {
                firedSentinel = new Exception("Project '" + project.getPath() + "' deferred configuration fired");
            }
            fired = true;
            try {
                for (Runnable runnable : configuration) {
                    runnable.run();
                }
            } finally {
                configuration.clear();
            }
        }
    }

}
