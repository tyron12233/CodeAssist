package org.gradle.api;

import com.google.common.collect.ImmutableList;
import org.gradle.api.internal.tasks.CompileServices;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.service.scopes.PluginServiceRegistry;

import java.util.List;

public class TestJavaTask extends BaseProjectTestCase {

    @Override
    protected List<PluginServiceRegistry> getPluginServiceRegistries() {
        return ImmutableList.of(new CompileServices());
    }

    @Override
    public void configure(Project project) {
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
