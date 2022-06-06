package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.tasks.CompileServices;
import com.tyron.builder.api.tasks.compile.JavaCompile;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;

import java.util.List;

public class TestJavaTask extends BaseProjectTestCase {

    @Override
    protected List<PluginServiceRegistry> getPluginServiceRegistries() {
        return ImmutableList.of(new CompileServices());
    }

    @Override
    public void configure(BuildProject project) {
        project.getTasks().register("compileJava", JavaCompile.class, new Action<JavaCompile>() {
            @Override
            public void execute(JavaCompile javaCompile) {
                javaCompile.setSource(project.fileTree(project.file("src")));
                javaCompile.setClasspath(project.fileTree(project.file("src")));
                javaCompile.getDestinationDirectory().set(project.file(project.getBuildDir() + "classes"));
            }
        });
    }

    @Override
    public List<String> getTasks() {
        return ImmutableList.of("compileJava");
    }
}
