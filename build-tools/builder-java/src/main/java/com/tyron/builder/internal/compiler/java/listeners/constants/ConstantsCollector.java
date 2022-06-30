package com.tyron.builder.internal.compiler.java.listeners.constants;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ConstantsCollector implements TaskListener {

    private final JavacTask task;
    private final Map<String, Collection<String>> mapping;
    private final ConstantDependentsConsumer constantDependentsConsumer;

    public ConstantsCollector(JavacTask task, ConstantDependentsConsumer constantDependentsConsumer) {
        this.task = task;
        this.mapping = new HashMap<>();
        this.constantDependentsConsumer = constantDependentsConsumer;
    }

    public Map<String, Collection<String>> getMapping() {
        return mapping;
    }

    @Override
    public void started(TaskEvent e) {

    }

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() == Kind.ANALYZE) {
            Trees trees = Trees.instance(task);
            ConstantsTreeVisitor visitor = new ConstantsTreeVisitor(task.getElements(), trees, constantDependentsConsumer);
            TreePath path = trees.getPath(e.getCompilationUnit(), e.getCompilationUnit());
            visitor.scan(path, null);
        }
    }

}
