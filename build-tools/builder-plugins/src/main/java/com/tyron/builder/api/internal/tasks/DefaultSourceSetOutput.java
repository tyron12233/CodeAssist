/*
 * Copyright 2010 the original author or authors.
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

package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.SourceSetOutput;
import com.tyron.builder.api.tasks.TaskDependency;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.internal.logging.text.TreeFormatter;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class DefaultSourceSetOutput extends CompositeFileCollection implements SourceSetOutput {
    private final ConfigurableFileCollection outputDirectories;
    private Object resourcesDir;

    private final ConfigurableFileCollection classesDirs;
    private final ConfigurableFileCollection dirs;
    private final ConfigurableFileCollection generatedSourcesDirs;
    private final FileResolver fileResolver;
    private final DefaultTaskDependency compileTasks;

    public DefaultSourceSetOutput(String sourceSetDisplayName, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory) {
        this.fileResolver = fileResolver;

        this.classesDirs = fileCollectionFactory.configurableFiles(sourceSetDisplayName + " classesDirs");
        // TODO: This should be more specific to just the tasks that create the class files?
        classesDirs.builtBy(this);

        this.outputDirectories = fileCollectionFactory.configurableFiles(sourceSetDisplayName + " classes");
        outputDirectories.from(classesDirs, (Callable) this::getResourcesDir);

        this.dirs = fileCollectionFactory.configurableFiles(sourceSetDisplayName + " dirs");

        this.generatedSourcesDirs = fileCollectionFactory.configurableFiles(sourceSetDisplayName + " generatedSourcesDirs");
        this.compileTasks = new DefaultTaskDependency();
    }

    @Override
    protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
        visitor.accept((FileCollectionInternal) outputDirectories);
    }

    @Override
    public String getDisplayName() {
        return outputDirectories.toString();
    }

    @Override
    protected void appendContents(TreeFormatter formatter) {
        formatter.node("source set: " + outputDirectories.toString());
        formatter.node("output directories");
        formatter.startChildren();
        ((FileCollectionInternal) outputDirectories).describeContents(formatter);
        formatter.endChildren();
    }

    @Override
    public ConfigurableFileCollection getClassesDirs() {
        return classesDirs;
    }


    /**
     * Adds a new classes directory that compiled classes are assembled into.
     *
     * @param directory the classes dir provider. Should not be null.
     */
    public void addClassesDir(Provider<Directory> directory) {
        classesDirs.from(directory);
    }

    @Override
    @Nullable
    public File getResourcesDir() {
        if (resourcesDir == null) {
            return null;
        }
        return fileResolver.resolve(resourcesDir);
    }

    @Override
    public void setResourcesDir(File resourcesDir) {
        this.resourcesDir = resourcesDir;
    }

    @Override
    public void setResourcesDir(Object resourcesDir) {
        this.resourcesDir = resourcesDir;
    }

    public void builtBy(Object... taskPaths) {
        outputDirectories.builtBy(taskPaths);
    }

    @Override
    public void dir(Object dir) {
        this.dir(Collections.emptyMap(), dir);
    }

    @Override
    public void dir(Map<String, Object> options, Object dir) {
        this.dirs.from(dir);
        this.outputDirectories.from(dir);

        Object builtBy = options.get("builtBy");
        if (builtBy != null) {
            this.builtBy(builtBy);
            this.dirs.builtBy(builtBy);
        }
    }

    @Override
    public FileCollection getDirs() {
        return dirs;
    }

    @Override
    public ConfigurableFileCollection getGeneratedSourcesDirs() {
        return generatedSourcesDirs;
    }

    public void registerClassesContributor(TaskProvider<?> task) {
        compileTasks.add(task);
    }

    public TaskDependency getClassesContributors() {
        return compileTasks;
    }

}
