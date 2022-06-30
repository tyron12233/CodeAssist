/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.internal.deployment;

import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.tasks.Classpath;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.work.DisableCachingByDefault;

import java.util.Collection;

@DisableCachingByDefault(because = "Produces no cacheable output")
public class RunApplication extends DefaultTask {
    private String mainClassName;
    private Collection<String> arguments;
    private FileCollection classpath;
//    private DeploymentRegistry.ChangeBehavior changeBehavior = DeploymentRegistry
//    .ChangeBehavior.RESTART;

    @Classpath
    public FileCollection getClasspath() {
        return classpath;
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath;
    }

    @Input
    public Collection<String> getArguments() {
        return arguments;
    }

    public void setArguments(Collection<String> arguments) {
        this.arguments = arguments;
    }

    @Input
    public String getMainClassName() {
        return mainClassName;
    }

    public void setMainClassName(String mainClassName) {
        this.mainClassName = mainClassName;
    }

    @TaskAction
    public void startApplication() {
//        DeploymentRegistry registry = getDeploymentRegistry();
//        JavaApplicationHandle handle = registry.get(getPath(), JavaApplicationHandle.class);
//        if (handle == null) {
//            JavaExecHandleBuilder builder = getExecActionFactory().newJavaExec();
//            builder.setExecutable(Jvm.current().getJavaExecutable());
//            builder.setClasspath(classpath);
//            builder.getMainClass().set(mainClassName);
//            builder.setArgs(arguments);
//            registry.start(getPath(), changeBehavior, JavaApplicationHandle.class, builder);
//        }
        throw new UnsupportedOperationException();
    }

//    @Inject
//    protected DeploymentRegistry getDeploymentRegistry() {
//        throw new UnsupportedOperationException();
//    }
//
//    @Inject
//    protected JavaExecHandleFactory getExecActionFactory() {
//        throw new UnsupportedOperationException();
//    }
//
//    @Internal
//    public DeploymentRegistry.ChangeBehavior getChangeBehavior() {
//        return changeBehavior;
//    }
//
//    public void setChangeBehavior(DeploymentRegistry.ChangeBehavior changeBehavior) {
//        this.changeBehavior = changeBehavior;
//    }
}
