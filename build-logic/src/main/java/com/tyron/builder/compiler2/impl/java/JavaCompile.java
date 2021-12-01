package com.tyron.builder.compiler2.impl.java;

import com.tyron.builder.compiler2.api.Action;
import com.tyron.builder.compiler2.api.Task;
import com.tyron.builder.project.experimental.JavaModule;

/**
 * Compiles Java source files.
 */
public class JavaCompile extends AbstractCompile {

    private JavaModule mModule;

    public JavaCompile(JavaModule module) {
        mModule = module;

        doLast(CompileAction.NAME, task -> {
            new CompileAction().execute(task);
        });
    }

    protected void compile() {

    }

    private class CompileAction implements Action<Task> {

        private static final String NAME = "compileJava";

        @Override
        public void execute(Task task) {

        }
    }
}
