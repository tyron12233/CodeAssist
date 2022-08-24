package org.gradle.launcher.daemon.context;

import org.gradle.api.internal.specs.ExplainingSpec;
import org.gradle.internal.jvm.Jvm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class DaemonCompatibilitySpec implements ExplainingSpec<DaemonContext> {

    private final DaemonContext desiredContext;

    public DaemonCompatibilitySpec(DaemonContext desiredContext) {
        this.desiredContext = desiredContext;
    }

    @Override
    public boolean isSatisfiedBy(DaemonContext potentialContext) {
        String unsatisfied = whyUnsatisfied(potentialContext);
        if (unsatisfied != null && unsatisfied.contains("Java home is different")) {
            return true;
        }
        return unsatisfied == null;
    }

    @Override
    public String whyUnsatisfied(DaemonContext context) {
        if (!javaHomeMatches(context)) {
            return "Java home is different.\n" + description(context);
        } else if (!daemonOptsMatch(context)) {
            return "At least one daemon option is different.\n" + description(context);
        } else if (!priorityMatches(context)) {
            return "Process priority is different.\n" + description(context);
        }
        return null;
    }

    private String description(DaemonContext context) {
        return "Wanted: " + this + "\n"
            + "Actual: " + context + "\n";
    }

    private boolean daemonOptsMatch(DaemonContext potentialContext) {
        return potentialContext.getDaemonOpts().containsAll(desiredContext.getDaemonOpts())
            && potentialContext.getDaemonOpts().size() == desiredContext.getDaemonOpts().size();
    }

    private boolean javaHomeMatches(DaemonContext potentialContext) {
        try {
            File potentialJavaHome = potentialContext.getJavaHome();
            if (potentialJavaHome.exists()) {
                File potentialJava = Jvm.forHome(potentialJavaHome).getJavaExecutable();
                File desiredJava = Jvm.forHome(desiredContext.getJavaHome()).getJavaExecutable();
                return Files.isSameFile(potentialJava.toPath(), desiredJava.toPath());
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    private boolean priorityMatches(DaemonContext context) {
        return desiredContext.getPriority() == context.getPriority();
    }

    @Override
    public String toString() {
        return desiredContext.toString();
    }
}
