package com.tyron.builder.internal.featurelifecycle;

import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.scripts.ScriptExecutionListener;

import javax.annotation.concurrent.ThreadSafe;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@ThreadSafe
public class ScriptUsageLocationReporter implements ScriptExecutionListener, UsageLocationReporter {
    private final Lock lock = new ReentrantLock();
    private final Map<String, ScriptSource> scripts = new HashMap<>();

    @Override
    public void onScriptClassLoaded(ScriptSource scriptSource, Class<?> scriptClass) {
        lock.lock();
        try {
            scripts.put(scriptSource.getFileName(), scriptSource);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reportLocation(FeatureUsage usage, StringBuilder target) {
        lock.lock();
        try {
            doReportLocation(usage, target);
        } finally {
            lock.unlock();
        }
    }

    private void doReportLocation(FeatureUsage usage, StringBuilder target) {
        List<StackTraceElement> stack = usage.getStack();
        if (stack.isEmpty()) {
            return;
        }

        StackTraceElement directCaller = stack.get(0);
        if (scripts.containsKey(directCaller.getFileName())) {
            reportStackTraceElement(directCaller, target);
            return;
        }

        int caller = 1;
        while (caller < stack.size() && stack.get(caller).getClassName().equals(directCaller.getClassName())) {
            caller++;
        }
        if (caller == stack.size()) {
            return;
        }
        StackTraceElement indirectCaller = stack.get(caller);
        if (scripts.containsKey(indirectCaller.getFileName())) {
            reportStackTraceElement(indirectCaller, target);
        }
    }

    private void reportStackTraceElement(StackTraceElement stackTraceElement, StringBuilder target) {
        ScriptSource scriptSource = scripts.get(stackTraceElement.getFileName());
        target.append(scriptSource.getLongDisplayName().getCapitalizedDisplayName());
        if (stackTraceElement.getLineNumber() > 0) {
            target.append(": line ");
            target.append(stackTraceElement.getLineNumber());
        }
    }
}
